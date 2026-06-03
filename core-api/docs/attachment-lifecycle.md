# 첨부(Attachment) 생명주기와 orphan 정리

> 최종 갱신: 2026-05-21
> 관련 변경: 루트 `docs/changelog.md` 의 "orphan 첨부 정리 — TTL GC + 명시적 삭제" 항목
> 이 문서는 첨부가 **어떤 상태를 거치는지**, **업로드했지만 케이스를 안 만든 경우(orphan)를 어떻게 정리하는지**, **왜 이렇게 설계했는지**, **어디를 보면 되는지**를 한 곳에 정리한다.

---

## 0. TL;DR — 한눈에 보기

첨부는 **케이스보다 먼저 업로드**되고, 케이스 생성 시 연결된다. 연결되지 않은 채 버려진 첨부(orphan)는 파일+DB row 가 누수되므로 정리가 필요하다.

```
    업로드             케이스 생성 시                영영 미연결(이탈/크래시)
      │                   │                              │
      ▼                   ▼                              ▼
    [미연결]   ──연결──▶  [연결됨]                        [orphan]
errorCaseId=null   errorCaseId=123            errorCaseId=null + 오래됨
      │                                                      │
      │ 사용자가 폼에서 제거 → 즉시 DELETE(B)                      │ TTL 경과 → 스케줄 GC(A)
      ▼                                                      ▼
        ─────────────  단일 삭제 루틴(파일+row, 멱등)  ─────────────
```

정리 전략 (실무 표준):
- **링크-온-서밋**: 케이스 생성 트랜잭션 안에서 첨부를 연결(원자적).
- **A. TTL GC (필수 안전망)**: 미연결 + TTL 경과 첨부를 스케줄 잡이 주기적으로 정리. 크래시/이탈은 이것만이 잡는다.
- **B. 명시적 삭제 (보조)**: 사용자가 UI 에서 첨부를 빼면 즉시 삭제 → orphan 총량을 낮게 유지.
- **단일 삭제 루틴**: A·B·(추후 케이스 cascade)가 **하나의 멱등 삭제 함수**를 공유.

---

## 1. 첨부 상태 모델

| 상태 | 조건 | 다운로드 권한 | 정리 |
|------|------|---------------|------|
| **미연결(pending)** | `errorCaseId IS NULL`, 최근 | 업로더 본인만(preview) | 대상 아님(아직 작성 중일 수 있음) |
| **연결됨(linked)** | `errorCaseId = <케이스>` | 케이스 owner | 케이스 삭제 시 함께(후속) |
| **orphan** | `errorCaseId IS NULL` + `uploaded_at < now - TTL` | 업로더 본인만 | **TTL GC 가 삭제** |

- 도메인: `Attachment.errorCaseId: Long?` (미연결이면 null). `embedToken()` = `@attach(markerId)`.
- 다운로드 권한 분기는 `DownloadAttachmentUseCase.authorize()` 에 이미 구현돼 있다(미연결=업로더, 연결=케이스 owner).

---

## 2. 흐름 / 시나리오

### 정상 — 업로드 후 케이스 생성
```
POST /api/v1/error-attachments (multipart)         → 파일 저장 + row(errorCaseId=null) → markerId 반환
POST /api/v1/error-cases { attachmentMarkerIds }   → CreateErrorCaseUseCase(@Transactional)
                                                       resolveAttachments(검증: 존재/소유자) → 케이스 저장 시 연결
```
연결은 케이스 생성과 **같은 트랜잭션**이라, 케이스 생성이 실패하면 연결도 롤백 → 첨부는 미연결로 남는다(=orphan 후보, GC 가 처리). 즉 "케이스는 안 만들어졌는데 첨부만 연결됨" 같은 불일치는 없다.

### orphan — 업로드만 하고 케이스 미생성
```
POST /api/v1/error-attachments  → 미연결 첨부 생성
(사용자 폼 이탈 / 브라우저 닫힘 / 크래시 — 케이스 POST 안 옴)
... TTL(기본 24h) 경과 ...
스케줄 GC → 파일+row 삭제
```

### 사용자가 폼에서 첨부 제거 (B)
```
DELETE /api/v1/error-attachments/{markerId}  → 업로더 본인 + 미연결 검증 → 즉시 삭제(204)
```

---

## 3. 단일 삭제 루틴 — `AttachmentDeleter`

모든 삭제 경로(GC / 명시삭제 / 추후 케이스 cascade)가 공유하는 **하나의 멱등 함수**.

```kotlin
@Transactional
fun delete(attachment: Attachment) {
    storage.delete(attachment.markerId, attachment.fileName)  // ① 파일 먼저 (deleteIfExists — 멱등)
    repository.deleteByMarkerId(attachment.markerId)          // ② row
}
```

핵심 성질:
- **파일 먼저 → row**: 중간 실패 시 "row 남고 파일 없음" 상태가 되는데, 이건 다음 GC 가 같은 미연결 row 를 다시 집어 (파일은 이미 없으니 그대로) row 만 정리 → **자기치유**. 반대 순서(row 먼저)면 "row 없는 파일"이 남아 별도 sweep 이 필요하므로 일부러 파일 먼저.
- **멱등**: `Files.deleteIfExists`(없어도 예외 X) + `deleteByMarkerId`(없으면 no-op). → 재시도/다중 인스턴스 동시 삭제가 무해.
- **건별 독립 트랜잭션**: GC 배치에서 한 건 실패가 다른 건을 롤백시키지 않음.

> 이 멱등성 덕분에 **삭제의 정확성은 분산 락 없이도 보장**된다. 락(ShedLock)은 중복 작업을 줄이는 효율 목적.

---

## 4. A. TTL GC — `PurgeOrphanAttachmentsUseCase` + 스케줄러

### 정리 정책 (use case)
```
threshold = now - orphanTtl
반복(최대 MAX_BATCHES):
  batch = findUnlinkedOlderThan(threshold, batchSize)   # 오래된 순 페이징
  비었으면 종료
  각 건: AttachmentDeleter.delete (실패는 로깅 후 계속)
  이번 배치 삭제 0건이면 종료(전부 실패 → 다음 주기 재시도)   # 무한 루프 방지
  마지막(부분) 배치면 종료
```
- 조회: `error_case_id IS NULL AND uploaded_at < threshold ORDER BY uploaded_at ASC` (페이징 limit=batchSize).
- 인덱스: `ix_attachment_orphan_gc (error_case_id, uploaded_at)`.

### 스케줄 + 다중 인스턴스 안전 (ShedLock)
```
@Scheduled(cron = core.attachment.orphan-gc-cron, 기본 0 */10 * * * *)   # 10분마다
@SchedulerLock(name="purgeOrphanAttachments", lockAtMostFor=PT9M, lockAtLeastFor=PT30S)
fun purge() = purgeOrphanAttachments.invoke()
```
- N개 인스턴스로 떠도 **한 시점에 한 인스턴스만** 실행(잡 이름 단위 락).
- 락 저장소: Postgres `shedlock` 테이블. 마이그레이션 도구가 없어 **`ShedLockEntity` JPA 엔티티로 ddl-auto 자동 생성**.
- `usingDbTime()`: 인스턴스 간 시계차와 무관하게 DB 시간 기준.
- `lockAtMostFor`: 인스턴스가 죽어 락을 못 풀어도 이 시간 후 자동 해제(데드락 방지). `lockAtLeastFor`: 너무 빨리 끝나도 최소 유지(시계 흔들림에 의한 중복 실행 방지).

---

## 5. B. 명시적 삭제 — `DeleteAttachmentUseCase` + `DELETE /{markerId}`

```
DELETE /api/v1/error-attachments/{markerId}   → 204 No Content
  - 업로더 본인만 (아니면 403)
  - 미연결(errorCaseId == null)만 (연결된 건 403 → 케이스 삭제 경로로)
  - 실제 삭제는 AttachmentDeleter 재사용
```
이미 케이스에 묶인 첨부를 이 엔드포인트로 못 지우게 막는 이유: 케이스 본문(`@attach(markerId)`)이 깨진 참조를 갖는 것을 방지.

---

## 6. 설정 (`core.attachment.*`)

| 키 | 기본값 | 의미 |
|----|--------|------|
| `core.attachment.orphan-ttl` | `24h` | 미연결 첨부를 orphan 으로 보는 경과 시간. 폼 오래 쓰는 사용자가 첨부를 잃지 않게 넉넉히 |
| `core.attachment.orphan-gc-batch-size` | `500` | GC 한 배치 처리량(긴 트랜잭션/락 방지) |
| `core.attachment.orphan-gc-cron` | `0 */10 * * * *` | GC 실행 주기(6필드 cron) |
| `core.attachment.storage-path` | — | 파일 저장 루트 |
| `core.attachment.public-base-url` | — | 공개 URL prefix |

---

## 7. 설계 결정과 근거

| 결정 | 이유 |
|------|------|
| **업로드-먼저 + 링크-온-서밋 + TTL GC** | GitHub/Slack/Notion/S3 lifecycle 등이 쓰는 표준. 미리보기·progressive 업로드 UX 유지하면서 누수는 GC 로 backstop |
| **TTL GC 가 필수** | 크래시/탭 닫힘/네트워크 끊김은 클라이언트가 정리 신호를 못 줌 → 서버 GC 만이 유일한 안전망 |
| **단일 멱등 삭제 루틴** | 삭제 경로가 갈라지면 일관성 깨짐. 멱등성으로 다중 인스턴스/재시도 안전 + 락 없이도 정확 |
| **파일 먼저 → row** | 잔여가 "row 있고 파일 없음"이 되어 다음 GC 가 자연 회수(자기치유). row-less 파일 sweep 부담 회피 |
| **ShedLock 분산 락** | 중복 스캔/작업 제거(효율). 정확성은 멱등성이 이미 보장하므로 락은 안전망이 아닌 최적화 |
| **shedlock 테이블을 JPA 엔티티로** | 현재 마이그레이션 도구(Flyway 등) 미도입 → ddl-auto 로 생성. 도구 도입 시 SQL 로 이전 |
| **draft-first/원자적 업로드 미채택** | draft-first 는 orphan 을 케이스로 이동시킬 뿐(+UX 부담), 원자적 업로드는 미리보기/진행률 포기. 둘 다 UX 손해가 큼 |

---

## 8. 운영 노트

- **다중 인스턴스**: ShedLock 으로 1대만 GC 실행. 설령 락이 없어도 `AttachmentDeleter` 멱등성으로 중복 삭제는 무해(정확성 보장).
- **파일 ↔ DB 일관성**: 파일/DB 는 한 트랜잭션으로 못 묶음(파일시스템 비트랜잭셔널). "파일 먼저→row" + 멱등으로 부분 실패를 다음 GC 가 회수.
- **인덱스**: 운영에선 `WHERE error_case_id IS NULL` 부분 인덱스가 더 효율적(JPA `@Index` 로는 표현 불가 → 마이그레이션 시).
- **관측**: GC 는 `[attachment-gc] purged N orphan attachments` 로 회수량 로깅. orphan 수가 계속 증가하면 **연결 로직 버그 신호** → 메트릭/알람 권장(후속).

---

## 9. 컴포넌트 / 파일 맵

| 관심사 | 파일 |
|--------|------|
| 단일 삭제 루틴(파일+row, 멱등) | `application/usecase/AttachmentDeleter.kt` |
| TTL GC 배치 루프 | `application/usecase/PurgeOrphanAttachmentsUseCase.kt` |
| 명시적 삭제(소유자+미연결 검증) | `application/usecase/DeleteAttachmentUseCase.kt` |
| DELETE 엔드포인트 | `presentation/web/ErrorCaseAttachmentController.kt` |
| 저장소 삭제(멱등) | `application/port/AttachmentStoragePort.kt` + `infrastructure/storage/LocalFileSystemAttachmentStorageAdapter.kt` |
| GC 조회/삭제 쿼리 | `application/port/ErrorCaseAttachmentRepositoryPort.kt` + `infrastructure/jpa/AttachmentJpaRepository.kt` + `.../adapter/AttachmentRepositoryAdapter.kt` |
| GC 인덱스 | `infrastructure/jpa/entity/AttachmentEntity.kt` (`ix_attachment_orphan_gc`) |
| 스케줄+락 설정 | `shared/scheduling/SchedulingConfig.kt` |
| shedlock 테이블 매핑 | `shared/scheduling/ShedLockEntity.kt` |
| 스케줄 트리거 | `infrastructure/scheduling/OrphanAttachmentGcScheduler.kt` |
| 설정 | `config/AttachmentStorageProperties.kt` |
| 의존성 | `core-api/build.gradle.kts` (shedlock 6.10.0) |

> 패키지 루트: `org.studieojavry.coreapi.errorcase.*` (shared 제외).

---

## 10. 후속 과제 (이번 범위 밖)

1. **케이스 삭제 시 첨부 cascade** — `AttachmentDeleter` 재사용해 연결 첨부도 함께 삭제.
2. **row 없는 파일 reconciliation sweep** — 업로드 중 크래시(파일 썼는데 DB insert 실패)로 생기는 row-less 파일. 스토리지 지표 드리프트 보이면 추가.
3. **GC 메트릭/알람** — 회수량·orphan 수 메트릭, orphan 증가 시 알람.
4. **부분 인덱스** — 마이그레이션 도구 도입 시 `WHERE error_case_id IS NULL` 부분 인덱스로 전환.
5. **shedlock 테이블 SQL 이전** — Flyway/Liquibase 도입 시 JPA 엔티티 → 마이그레이션 SQL.
