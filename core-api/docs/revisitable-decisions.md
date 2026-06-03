# 재검토 가능한 설계 결정 (Revisitable Decisions)

이 문서는 **"지금은 이렇게 했지만, 요구사항·규모·운영 상황에 따라 바뀔 수 있는"** 설계 결정을 모아둔다.
커밋/changelog 가 "무엇을 했나"의 시간순 기록이라면, 이 문서는 **"왜 그렇게 골랐고, 어떤 다른 길이 있으며, 언제·어떻게 바꾸면 되는가"** 를 한 눈에 보기 위한 것이다.

각 항목은 아래 틀을 따른다:

- **현재 구현** — 지금 코드가 실제로 하는 동작.
- **의도/근거** — 왜 이 방식을 골랐나(특히 안전성·단순성 트레이드오프).
- **다른 선택지** — 고려했거나 가능했던 대안과 장단점.
- **확장/변경 방법** — 바꾸기로 하면 어디를 어떻게 손대면 되는가.
- **재검토 트리거** — 어떤 상황이 오면 다시 들여다봐야 하는가.
- **관련 코드** — 출발점이 되는 파일/심볼.

> 결정이 실제로 바뀌면, 이 문서의 해당 항목을 갱신하고 루트 `docs/changelog.md` 에도 기록한다.

---

## 1. 케이스 수정 시 스니펫/첨부 "제거"의 정리(cleanup) 방식

*기록: 2026-05-27*

에러케이스 수정(PATCH)에서 스니펫/첨부를 **선언형으로 재연결**한다(요청의 marker 집합 = 최종 상태, diff 로 추가/제거). 이때 **"제거"된 항목을 물리적으로 언제·어떻게 지우는가**에 대한 결정이다.

관련 배경:
- 스니펫/첨부는 **독립 생성 → markerId 발급 → 케이스에 연결(`errorCaseId` FK)** 되는 별도 리소스. 한 항목은 **한 케이스에만** 속한다(연결 시 `errorCaseId == null` 강제).
- 케이스 조회는 `findAllByErrorCaseId`(연결된 것만) 로 가져온다.

### 현재 구현

제거(`toRemove`) = **연결 해제만**. PATCH 트랜잭션 안에서는 `errorCaseId = null` 로 컬럼만 플립한다(`unlinkFromCase`, 벌크 `@Modifying`).

- 사용자 화면에서는 **즉시 사라진다** — 조회가 연결된 것만 가져오므로 본문 인라인·"관련 코드" 섹션 모두에서 빠진다.
- 물리적 삭제(스니펫 row / 첨부 row+파일)는 **orphan GC** 가 나중에 처리한다.
  - GC 대상 = `errorCaseId IS NULL` + `uploadedAt` 이 **TTL(기본 24h)** 경과.
  - 삭제는 `SnippetDeleter`/`AttachmentDeleter` 단일 루틴(각 호출 독립 트랜잭션, 멱등)이 수행. 케이스 *삭제* cascade 도 같은 deleter 를 공유.

→ 요약: **"숨김은 즉시, 청소는 나중(최대 24h)".**

### 의도/근거

1. **트랜잭션/크래시 안전성.** 파일 삭제는 트랜잭션 밖 부수효과다. PATCH 는 본문·메타·스니펫·첨부를 한 트랜잭션에서 여러 단계로 고치는데, 중간에 파일을 지웠다가 뒤 단계 실패로 롤백되면 "파일은 사라졌는데 row 는 살아있는" dangling 이 생긴다. 그래서 PATCH 안에서는 **DB 컬럼 플립만**(100% 롤백·크래시 안전) 하고, 위험한 파일 I/O 는 GC 라는 **단일 작업 트랜잭션**으로 몰아뒀다. (`AttachmentDeleter` 주석의 "파일 먼저 → row, 실패 시 다음 GC 가 자기치유" 가 이 설계를 보강.)
2. **삭제 경로 단일화.** orphan GC / 명시적 삭제 / 케이스삭제 cascade 가 같은 deleter 를 재사용해 정리 로직이 갈라지지 않는다.
3. **부수 효과로 얻은 24h undo 창.** 떼인 항목은 24h 동안 orphan 으로 살아있으므로, 같은 세션에서 다시 붙이면(소유·미연결 검증 통과) 재연결된다 → 사실상 실행취소.

### 다른 선택지

| 방식 | 화면 반영 | 물리적 정리 | txn/크래시 안전 | undo 창 | 케이스삭제와 일관성 |
|---|---|---|---|---|---|
| **(현재) unlink → orphan → GC** | 즉시 | ~24h 후 | ✅ 컬럼 플립만 | ✅ 24h | ❌ (cascade 는 즉시) |
| (A) PATCH 안에서 deleter 인라인 호출 | 즉시 | 즉시 | ⚠️ 파일 I/O 가 txn 에 끼어듦(롤백 시 dangling) | ❌ | ✅ |
| (B) **afterCommit 즉시 정리 + GC 백스톱** | 즉시 | 즉시(커밋 후) | ✅ 파일 I/O 가 txn 밖 | ❌ | ✅ |

- (A) 는 스니펫(파일 없음)엔 사실상 안전하지만 첨부(파일)엔 위험.
- (B) 가 "즉시 청소 + 트랜잭션 안전"을 모두 잡는 정석. undo 창만 포기.

### 확장/변경 방법 — (B) afterCommit 즉시 정리로 올리기

1. `UpdateErrorCaseUseCase.reconcile*` 에서 `toRemove` 의 marker 들을 모은다(현행처럼 `unlinkFromCase` 로 컬럼 플립은 유지 — 롤백 안전).
2. `TransactionSynchronizationManager.registerSynchronization(...)` 의 `afterCommit()` 훅에서 그 marker 들에 대해 `SnippetDeleter`/`AttachmentDeleter.delete(...)` 를 호출(best-effort).
3. orphan GC 는 그대로 둔다 — "커밋과 정리 사이 크래시" 등 누락분을 줍는 **백스톱**.

→ 결과: "수정에서 제거 = 즉시 삭제(케이스삭제 cascade 와 동일)", GC 는 안전망. 비용은 작다(reconcile 에서 제거 marker 수집 + 훅 등록).

### 재검토 트리거

- 첨부 파일 스토리지 비용이 커져 **24h 지연 회수**가 부담될 때 → (B) 검토.
- "수정 제거는 24h 남는데 케이스 삭제는 즉시"라는 **비대칭**이 운영/지원 이슈가 될 때 → (B).
- 명시적 **undo/휴지통** 기능을 정식 도입할 때 → 현재의 암묵적 24h 창을 명시적 상태(soft-delete)로 승격.
- 스니펫/첨부를 **여러 케이스가 공유**하도록 모델이 바뀌면 → "제거 = 삭제" 전제 자체가 깨지므로 전면 재설계(제거는 참조카운트 감소로).

### 관련 코드

- `application/usecase/UpdateErrorCaseUseCase` — `reconcileSnippets`/`reconcileAttachments`(diff, `unlinkFromCase` 호출).
- `application/usecase/SnippetDeleter`, `AttachmentDeleter` — 단일 삭제 루틴(파일→row, 멱등).
- `application/usecase/PurgeOrphanSnippetsUseCase`, `PurgeOrphanAttachmentsUseCase` — orphan GC 배치.
- `config/SnippetProperties`, `config/AttachmentStorageProperties` — `orphanTtl`(기본 24h), `orphanGcBatchSize`.
- 참고: `core-api/docs/attachment-lifecycle.md`.

---

## 2. 스니펫/첨부 "내용 수정" 엔드포인트

*기록: 2026-05-27 · 갱신: 2026-05-27(스니펫 구현 완료)*

### 현재 구현

- 스니펫: `POST`(생성) · **`PATCH /{markerId}`(내용 수정, 2026-05-27 추가)** · `DELETE /{markerId}`. → 내용 수정 **구현됨**.
  - 업로더만, **markerId·uploadedBy·uploadedAt·errorCaseId 고정**한 채 `title/language/filePathOrClass/lineRange/caption/code` 부분 수정(null=유지). 케이스에 연결된 스니펫도 수정 가능.
- 첨부: `POST`(업로드) · `GET /{markerId}`(다운로드) · `DELETE /{markerId}` 만. **메타 수정 엔드포인트는 아직 없음.**
- 케이스 PATCH 의 `snippetMarkerIds`/`attachmentMarkerIds` 는 **연결(소속) 집합**만 바꾼다 — 스니펫의 `code` 등 **내용은 건드리지 않는다**(의도된 책임 분리).

### 의도/근거

개념을 3층으로 분리한다 — **① 소속(링크)** / **② 본문 태그(`@snippet(markerId)` 임베드)** / **③ 내용(code 등)**.
케이스 PATCH 는 ①(`snippetMarkerIds`)과 ②(`description` 텍스트)를 책임지고, ③ 은 스니펫/첨부 리소스 자신의 PATCH 가 책임진다. "책꽂이(케이스)에 책을 넣고 빼는 일"과 "책 글자를 고치는 일"을 분리. markerId 를 고정하므로 ③ 을 고쳐도 ②(본문 `@snippet(markerId)` 참조)가 안 깨진다.

### 남은 확장 — 첨부 메타 수정

- 첨부 파일 자체는 보통 in-place 수정이 없다(교체 = 새 업로드). 필요한 건 **메타(title/caption) PATCH** 정도 → `PATCH /api/v1/error-attachments/{markerId}` 를 스니펫과 같은 패턴으로 추가하면 된다(업로더만, markerId·파일·uploadedBy 고정).

### 재검토 트리거

- 첨부의 title/caption 을 케이스와 별개로 고치고 싶다는 요구가 확인될 때 → 첨부 메타 PATCH 추가.

### 관련 코드

- 스니펫 수정: `presentation/web/SnippetController`(`PATCH /{markerId}`), `application/usecase/UpdateSnippetUseCase`, `application/command/UpdateSnippetCommand`, `presentation/.../CodeSnippetUpdateRequest`·`CodeSnippetDetailResponse`.
- 어댑터 upsert: `infrastructure/jpa/adapter/CodeSnippetRepositoryAdapter.save`(내용 6필드만 갱신, uploadedBy/At·errorCaseId 보존).
- 첨부(메타 PATCH 미구현): `presentation/web/ErrorCaseAttachmentController`.

---

## 3. 에러케이스 목록 조회: 필터 전달·동적 쿼리·페이징 방식

*기록: 2026-05-27*

`GET /api/v1/error-cases` 의 세 가지 결정을 묶는다 — **(a) 필터를 어떻게 받나, (b) 동적 쿼리를 어떻게 짜나, (c) 어떻게 페이징하나**.

### 현재 구현

- **(a) 필터 = flat `@RequestParam`.** `workspaceId` / `status` / `severity` / `fingerprint` / `cursor` / `size` 를 모두 평면 쿼리 파라미터로 받는다(전부 `required=false`, `size` 기본 20·상한 100). `status` 는 컨트롤러에서 enum 파싱(실패 시 400).
- **(b) 동적 쿼리 = Criteria API.** `ErrorCaseRepositoryAdapter.search` 가 **필터가 non-null 일 때만 predicate 를 추가**한다. JPQL 의 `:param IS NULL OR e.x = :param` 방식은 쓰지 않는다.
- **(c) 페이징 = cursor(keyset) 무한 스크롤.** 정렬 `createdAt DESC, id DESC`. keyset 조건 `createdAt < c OR (createdAt = c AND id < cId)`. 커서는 `"createdAt|id"` 를 Base64URL 로 인코딩한 불투명 문자열. `hasNext` 는 `size+1` 조회로 판정, 응답은 `{ items, nextCursor, hasNext }`. **전체 개수(total)는 제공하지 않는다.**
- 스코프: `workspaceId` 지정 시 멤버(READ+)면 워크스페이스 전체, 미지정 시 본인 소유 케이스.

### 의도/근거

- **(a) flat 파라미터**: 필터가 단순한 동치/소수라 `GET` + 쿼리스트링이 가장 표준적이고 캐시·북마크·로깅 친화적. 검색 전용 `POST {body}` 는 부수효과 없는 조회에 동사가 안 맞고 캐시도 못 탄다.
- **(b) Criteria API**: JPQL 로 `:param IS NULL` + null 바인드를 쓰면 Postgres 가 파라미터 타입을 추론 못 해 **`could not determine data type`(42P18)** 으로 터졌다(실제 500 겪음). 필터 있을 때만 predicate 를 더하는 Criteria 로 회피 — null 바인드 자체가 없어진다.
- **(c) cursor/keyset**: 에러케이스 피드는 **append-heavy** 라, offset 페이징은 새 데이터가 끼면 페이지 경계가 밀려 **중복/누락(drift)** 이 생기고 깊은 페이지일수록 느리다. keyset 은 drift 없고 인덱스 타면 일정 성능. 무한 스크롤 UX 와도 맞는다. total 을 빼서 매 페이지 `COUNT(*)` 비용도 없앴다.

### 다른 선택지

| 축 | 현재 | 대안 | 트레이드오프 |
|---|---|---|---|
| (a) 필터 전달 | flat `@RequestParam` | `POST /search` body / RSQL·필터 DSL | 필터가 많아지고 OR·범위·중첩이 필요해지면 평면 파라미터가 폭발 → DSL/스펙 객체가 유리 |
| (b) 동적 쿼리 | Criteria API | QueryDSL / Spring Data `Specification` / MyBatis 동적 SQL | Criteria 는 타입세이프하나 **장황**. 필터·정렬 조합이 늘면 QueryDSL 가독성↑ (의존성 추가 필요) |
| (c) 페이징 | cursor(keyset), total 없음 | offset 페이지네이션 / cursor + 별도 count | 점프(N페이지로)·"총 1,234건" UI 가 필요하면 offset 또는 근사 count 병행 |

### 확장/변경 방법

- **필터가 많아지면**: `@RequestParam` 들을 `ErrorCaseListQuery` 같은 **DTO 1개로 묶어** `@ModelAttribute` 바인딩(컨트롤러 시그니처 안 터지게). 더 복잡(자유 조합/OR/범위)해지면 RSQL 파서 또는 필터 스펙 객체 도입.
- **동적 쿼리가 장황해지면**: `search` 를 **QueryDSL** 로 교체(포트 시그니처 `ErrorCaseSearchCriteria → List<ErrorCaseSummary>` 는 그대로 두고 어댑터 내부만 바꾸면 됨 — 헥사고날 덕분에 영향 국소).
- **점프/총개수 UI 요구 시**: cursor 는 유지하되 **별도 `count(criteria)`** 엔드포인트/필드를 옵션으로 추가(근사치 허용 시 `reltuples` 등). 관리자 화면만 offset 을 따로 둘 수도.
- **정렬 기준 추가**(occurredAt, severity 순 등): keyset 비교 컬럼이 정렬 키와 같아야 하므로, 커서 인코딩(`createdAt|id`)에 정렬 키를 포함하도록 확장하고 인덱스도 같이.

### 재검토 트리거

- 필터 종류/조합이 늘어 컨트롤러 파라미터가 5~6개를 넘어 가독성이 떨어질 때 → DTO 묶기/DSL.
- Criteria 빌더가 길어지고 정렬·조인이 다양해질 때 → QueryDSL.
- 프런트가 "페이지 점프"나 "총 N건" 표시를 요구할 때 → count/offset 병행.
- 새 정렬 기준이 필요할 때 → 커서·인덱스 확장.

### 관련 코드

- `presentation/web/ErrorCaseController.list` — `@RequestParam` 필터, status enum 파싱(→400).
- `application/usecase/ListErrorCasesUseCase` — 스코프/권한, 커서 encode/decode, `size+1` hasNext, `MAX_SIZE=100`.
- `application/port/ErrorCaseSearch`(`ErrorCaseSearchCriteria`/`ErrorCaseSummary`).
- `infrastructure/jpa/adapter/ErrorCaseRepositoryAdapter.search` — Criteria 동적 predicate + keyset. (JPQL `:param IS NULL` 회피 주석.)

---

## 4. Solution 의 템플릿화 / 재사용 / 매칭

*기록: 2026-05-29 · 상태: **미구현 — 설계 분석만 등재**.*

본 프로젝트의 핵심 가치 제안("**에러 아카이브** = 같은 에러를 만난 사람들의 해결 경험을 모아 재사용") 에 직결되는 확장 축이다. 어떤 사용자가 step 으로 시도하며 만든 solution 을, 같은/비슷한 에러를 만난 다른 사용자가 빠르게 참조·적용할 수 있게 하는 것. 모델 결정이 갈래가 많고 사용자 데이터 누적에 따라 정답이 바뀌므로, 먼저 **설계 결정의 지형도** 만 등재하고 본격 구현은 사용자 트래픽이 생긴 뒤 재논의한다.

### "템플릿화" 의 네 갈래 의미

사용자가 "템플릿화" 라고 했을 때 의도가 다음 중 어디인지부터 정해야 의사결정이 흐트러지지 않는다:

| 갈래 | 무엇 | 비유 |
|---|---|---|
| **(a) 내 재사용** | 내가 만든 solution 을 내 다른 케이스에 가져다 쓰기 | 즐겨찾기 / 클립보드 |
| **(b) 팀 공유** | 워크스페이스 동료가 정리한 solution 을 같은 팀이 재사용 | 팀 위키 스니펫 |
| **(c) 공개 카탈로그** | "이 에러는 보통 이렇게 해결합니다" 의 전사/공개 라이브러리 | Stack Overflow / Cookbook |
| **(d) 자동 매칭/추천** | 새 케이스 등록 시 비슷한 에러의 solution 을 *자동* 으로 제안 | IDE 의 quick-fix |

장기 목표는 (d)+(b)+(c) 의 조합. 가장 매력적이지만 비용도 큼.

### 매칭 키 (같은 에러를 어떻게 판정하나)

| 키 | 정확도 | 비용 | 활용 시점 |
|---|---|---|---|
| **`fingerprint`** 정확 일치 (`SHA-256(exceptionClass + normalized stack)`) | ★★★ | 0 (이미 인덱스) | Phase 1 의 기본 |
| **exceptionClass** | ★ | 0 | 보완(너무 광범위) |
| **fingerprint prefix / stack 공통 부분** | ★★ | 작음 | Phase 1 확장 |
| **사용자 태그·카테고리** | ★★ | 입력 부담 | Phase 3 |
| **자유 텍스트 검색 (FTS)** | ★ | 중간 | 보조 |
| **임베딩(LLM)** | ★★★ 시맨틱 | $/지연/외부 의존 | Phase 4 |

`fingerprint` 가 강력한 1차 시그널 — 라인 번호 정규화로 변경에 강하다. 1차 매칭에서 결과가 적을 때 stack 공통 prefix 로 점진 확장.

### 데이터 모델 옵션

| 안 | 무엇 | 장점 | 함정 |
|---|---|---|---|
| **A. 참조/링크형** | 다른 케이스의 solution 을 *읽기 전용* 으로 표시. 복제 X | 모델 변경 거의 없음. 권한 필터만 | 사용자가 보고 손으로 옮겨 적어야 — 적용 마찰 |
| **B. 인스턴스 복제** | "적용" 버튼 → solution 의 step 들을 새 케이스에 step 으로 **복제**(스니펫·첨부도 복제) | 적용 즉시. 자기 컨텍스트에 맞게 수정 가능 | step body 의 파일경로/변수명 잡음. 첨부 storage 복제 비용 |
| **C. SolutionTemplate 추상화** | 별도 엔티티(`title`, generalized step bodies, `tags`, `fingerprintHints`, `visibility`) | "재사용 가능한 처방전". 카탈로그/검색/즐겨찾기 가능 | 추상화 노동 ↑. 사용자가 안 만들면 빈 카탈로그 |
| **D. 케이스 자체 = 템플릿** | solution 있는 케이스를 검색·복제 단위로 본다 | 별도 엔티티 군더더기 없음 | "케이스" 와 "처방" 의미가 섞임 |

### 가시성 정책

| 정책 | 효과 |
|---|---|
| 본인 케이스만 | 안전. 가치는 (a) 만 |
| **본인 + 내가 멤버인 워크스페이스** | (a)+(b). 자연스러운 default |
| 위 + 사용자가 `visibility: PUBLIC` 으로 명시 공개 | (c) 추가. 큰 가치, 사일로 깸 |
| 자동 전부 공개 | 사용자 마찰(프라이버시·민감 코드) |

워크스페이스 케이스의 solution 을 PUBLIC 승격하려면 **워크스페이스 ADMIN 만** 가능하게 — 일반 멤버가 비공개 자산을 외부로 흘리는 사고 방지.

### 점진 도입 4단계 (비용 ↑ 가치 ↑)

```
Phase 1 — 자동 매칭 + 참조(읽기)                                [작음]
    새 케이스 상세 화면에 "이 에러를 봤던 다른 사용자의 solutions"
    사이드 패널. fingerprint 정확 일치를 백엔드가 권한 필터 후 반환.
    데이터 모델 변경: 없음 (인덱스만).
    엔드포인트 1개:
        GET /api/v1/error-cases/{id}/related-solutions?match=fingerprint

Phase 2 — "이 solution 적용" (복제)                             [중간]
    버튼 → solution 의 step 들을 현재 케이스에 step 으로 복제.
    스니펫·첨부는 새 인스턴스로 복제(원본 케이스 의존 끊기).
    원본은 보존, 복제본은 사용자가 자유 수정.
    모델 변경: Step.derivedFromStepId? (감사용, nullable).
    엔드포인트:
        POST /api/v1/error-cases/{targetId}/apply-solution { sourceSolutionId }

Phase 3 — SolutionTemplate 정식 도입                            [큼]
    "이 solution 을 템플릿으로" 명시. 별도 엔티티
    `SolutionTemplate(title, tags, generalized step bodies,
    fingerprintHints, visibility, popularity)`.
    템플릿 카탈로그 페이지 · 검색 · 즐겨찾기 · 버전.
    Phase 1·2 의 매칭/복제 대상이 "케이스 의 solution" → "Template" 으로 확장.

Phase 4 — 시맨틱 매칭 (임베딩/AI)                                [매우 큼]
    `exceptionMessage` + step.title/insight 를 임베딩 벡터화.
    "비슷한 에러" 추천(fingerprint 안 맞아도). 외부 LLM API · pgvector.
    운영 비용 · API 키 관리.
```

### 풀어야 할 어려움

- **컨텍스트 잡음**: step body 에 환경 의존 정보(파일경로, 변수명, 사내 서비스명) 가 묻어 있다. Phase 2 복제 UX 에서 "검토하고 수정" 강제 흐름 필요.
- **스니펫·첨부 cross-case 공유**: 현재 `errorCaseId` 단일 FK 라 cross-case 참조 불가. Phase 2 는 **복제** 가 정답(공유 X). 첨부 파일은 같은 storage 객체 재사용 + 새 row 만(스토리지 비용 절감) — 단 storage 정책 결정 필요.
- **권한 누수**: 매칭 결과는 반드시 `ErrorCaseAccess.requireRead` 통과한 케이스의 solution 만 반환. 응답 build 단계에서 한 번 더 마스킹.
- **중복 폭증/큐레이션**: 같은 에러에 N명이 각자 solution → 신호/잡음 비율 악화. Phase 3 에서 좋아요·검증 마크·인용 카운트·정렬.
- **버전 표류**: 원본 solution 수정 시 복제본 영향? → **복제 시점 스냅샷** 으로 잘라 영향 차단. 원본 추적은 `derivedFromStepId` 로 감사만.
- **케이스 status 와의 상호작용**: 매칭된 solution 의 원본 케이스가 RESOLVED 인지·SUCCESS step 이 있는지 등을 신뢰 시그널로 노출(품질 가중).

### 확장/변경 방법 — 단계별 산출물 요약

- **Phase 1**: 모델 0. `GET /error-cases/{id}/related-solutions` 하나 + `ErrorCaseAccess.requireRead` 권한 필터 + fingerprint 인덱스 활용.
- **Phase 2**: `Step.derivedFromStepId?` 컬럼 추가. `POST /error-cases/{id}/apply-solution` 엔드포인트. 스니펫/첨부 deep-clone 헬퍼.
- **Phase 3**: `SolutionTemplate` 도메인/엔티티/포트 + `PromoteSolutionToTemplate` 유스케이스 + 카탈로그 검색. `visibility`/`tags`/`popularity` 모델. 워크스페이스 ADMIN 승격 정책.
- **Phase 4**: 임베딩 컬럼(`pgvector`) + 외부 LLM API 통합 + 인프라(키·캐시·rate-limit).

### 재검토 트리거

- **사용자가 "이 에러 다른 사람은 어떻게 풀었지?" 라는 흐름을 명시 요청** → Phase 1 시작.
- 케이스 등록량이 늘어 같은 fingerprint 그룹의 case 수가 5+ 가 보이기 시작 → Phase 2 의 ROI 정당화.
- 사용자가 자기 solution 을 "공유 카탈로그에 올리고 싶다" 표명 → Phase 3.
- fingerprint 매칭이 부족하다는 피드백(다른 에러지만 본질 같음 사례) 누적 → Phase 4 검토.

### 관련 코드 (현재 활용 가능 + 추후 추가될 것)

활용 가능:
- `domain/model/vo/Fingerprint`, `shared/util/FingerprintGenerator` — SHA-256 (`exceptionClass + normalized stack`).
- `infrastructure/jpa/entity/ErrorCaseEntity.snapshot.fingerprint` — `ix_error_case_fingerprint` 인덱스(이미 존재).
- `application/usecase/ErrorCaseAccess` — 권한 필터 헬퍼(매칭 결과 마스킹에 재사용).
- `domain/model/Solution`, `Step` — 복제 대상.

추후 추가:
- (Phase 1) `application/usecase/FindRelatedSolutionsUseCase`, `presentation/web/RelatedSolutionsController`.
- (Phase 2) `Step.derivedFromStepId`, `ApplySolutionUseCase`, 스니펫/첨부 클로너.
- (Phase 3) `domain/model/SolutionTemplate`, `presentation/web/SolutionTemplateController`.
- (Phase 4) `infrastructure/embedding/*`, `pgvector` 의존성.

> 참고: `core-api/docs/errorcase-domain.md` §10 "확장 포인트" 의 *"Step 본문 의 step 전용 임베드 모델"* 결정도 Phase 2 의 스니펫·첨부 cross-case 처리 정책과 함께 다시 본다.
>
> 본 §4 의 매칭·복제·SolutionTemplate 도 §6 의 `insight-api` 와 *같은 BC 분리 트리거 그룹(Discovery)* 에 속한다 — 셋 다 derived read model 이라 *함께 떠나는* 게 자연스럽다.

---

## 5. 사용자 Settings / Profile 의 Information Architecture (IA)

*기록: 2026-05-29 · 상태: 설계 등재 (Phase 1 백엔드 구현 진행 중).*

"마이페이지·속성 페이지 어디에 무엇을 둘 것인가" 에 대한 결정. 항목이 많아질 가능성이 높고(프로필·계정·환경·알림·프라이버시·워크스페이스·개발자·데이터), 한 번 잘못 묶으면 사용자가 길을 잃거나 비공개를 공개로 노출하는 사고가 난다. IA 결정과 단계적 도입 순서를 한 곳에서 트래킹.

### 채택안 — "공개 프로필 ↔ 사적 Settings" 분리 (Pattern B)

```
TopBar 우상단 아바타 ⌄
   ├─ My profile   → /users/{me}        (공개 프로필)
   ├─ Settings     → /settings          (사적 설정)
   └─ Log out

/users/{userId}   ← 다른 사용자도 볼 수 있는 공개 프로필
   - displayName / avatar / bio
   - follower·following / mutual
   - 공개 에러케이스 목록 (visibility=PUBLIC, core-api 가 별도 노출)
   - [본인일 때만] "프로필 편집" → /settings/account

/settings  ← 본인만, 좌측 탭 7개
   Account        프로필 편집 · 연결된 OAuth · 회원 탈퇴
   Preferences    language / timezone / theme / default landing
   Notifications  채널(email/in-app/push) × 이벤트 매트릭스 + digest
   Privacy        기본 visibility(PRIVATE/WORKSPACE/PUBLIC) · 프로필 공개 · 검색 indexing
   Workspaces     워크스페이스별 알림 override · 기본 워크스페이스
   Developer      (옵션) API tokens
   Data           내 데이터 export · 탈퇴 단축
```

### 다른 선택지

| 패턴 | 구조 | 채택 안 한 이유 |
|---|---|---|
| **A. 통합 Settings + 탭만** | `/settings` 안에 다 | 공개 프로필이 *남이 보는 페이지* 인지 *나만의 페이지* 인지 사용자 멘탈모델 흐림 |
| **B. 공개↔사적 분리 ⭐ 채택** | `/users/{me}` + `/settings` | 업계 표준(GitHub/Linear/Figma). 멘탈모델 명확 |
| **C. 마이페이지 all-in-one** | `/me` 안에 다 | 공개·사적 섞여 실수 위험(비공개를 PUBLIC 으로 잘못 토글) |
| **D. 우상단 드롭다운만** | 페이지 없이 메뉴만 | 항목 7~8개에 부족 |

업계 reference: GitHub `/settings/*`(탭 30+) + `/{username}`, Notion `Settings & Members` 모달, Linear `/settings/*` 탭, Figma 동일.

### 알림 — 가장 복잡한 섹션의 UX 모델

이벤트 × 채널 매트릭스 폭주 위험. 채택안: **그룹별 + 펼치기**(GitHub Watching 패턴).

```
Channels (마스터)              [email ON] [in-app ON] [push OFF]
Digest                         ◉ 즉시 / ○ 일간 / ○ 주간

이벤트별
  📁 협업 (Collaboration)      ▼ 펼침
     • 내 케이스에 step 달림      email ✅  in-app ✅  push ⬜
     • 내 케이스에 solution      email ✅  in-app ✅  push ⬜
  📁 워크스페이스                ▶ 접힘
  📁 소셜                       ▶ 접힘
  📁 매칭/추천                  ▶ 접힘
```

**두 층 정책 — 글로벌 + 워크스페이스 오버라이드** (GitHub Watching 과 동일):
```
notification_policy_global             (user_id, event_key, channel) → ON/OFF
notification_policy_workspace_override (user_id, workspace_id, event_key, channel) → ON/OFF
```
사용자 단위 default 가 있고, 워크스페이스별로만 다르게 받고 싶으면 override row 가 우선.

다른 선택지(채택 안 함):
- 마스터 토글만 — 통제력 부족.
- 채널별 마스터만 — 이벤트별 조정 X.
- 전체 매트릭스 노출 — 화면 빽빽·압박감.
- "즉시 vs 다이제스트만 분리" — 익숙치 않음.

### 단계적 도입 — Phase 1 ~ 5

| Phase | 내용 | 모델 차원 | 백엔드 변경 |
|---|---|---|---|
| **1 ⭐ 첫 산출물** | `/settings/account` (프로필·OAuth·탈퇴) + `/settings/preferences` (language/timezone/theme/default landing) + `/users/{id}` 공개 프로필 | 1차원: `iam_user` 행에 컬럼 4개 추가 | `PATCH /users/me` 확장, `GET /users/{id}` 신설, `GET /users/me/connections` |
| **2** | Privacy 탭 — 새 케이스 기본 visibility, 검색 indexing opt-out, 프로필 공개 토글 | `iam_user` + `error_case.visibility`(core-api) | search/visibility 도입과 함께 |
| **3** | Notifications 탭 — 그룹+펼치기 매트릭스 + digest + 글로벌 정책 | **3차원**: user × event × channel | `notification_policy_global` 테이블 + 정책 CRUD + noti-api 본격 |
| **4** | Workspaces 탭 — 워크스페이스별 오버라이드 | + workspace 차원 | `notification_policy_workspace_override` 테이블 |
| **5** | Developer (API tokens) · Data export (GDPR) | 별도 도메인 | `iam_api_token` 테이블, export job |

### 백엔드 엔드포인트 트리 (Phase 별 노출)

```
Phase 1
  GET    /api/v1/users/me                          (확장: language/timezone/theme/defaultWorkspaceId)
  PATCH  /api/v1/users/me                          (확장)
  GET    /api/v1/users/{userId}                    (신설 — 공개 프로필)
  GET    /api/v1/users/me/connections              (신설 — 연결된 OAuth 목록)

Phase 2
  PATCH  /api/v1/users/me/privacy                  ← default-visibility 등

Phase 3
  GET    /api/v1/users/me/notifications            ← 글로벌 정책 전체
  PATCH  /api/v1/users/me/notifications            ← 마스터 토글
  PATCH  /api/v1/users/me/notifications/events/{key}

Phase 4
  GET    /api/v1/users/me/notifications/workspaces/{wsId}
  PATCH  /api/v1/users/me/notifications/workspaces/{wsId}
  DELETE /api/v1/users/me/notifications/workspaces/{wsId}     ← override 원복

Phase 5
  POST   /api/v1/users/me/api-tokens
  GET    /api/v1/users/me/api-tokens
  DELETE /api/v1/users/me/api-tokens/{id}
  POST   /api/v1/users/me/data-export
```

### 즉시 저장 vs 명시적 저장
- **토글류**(theme switch, 알림 on/off): 즉시 저장(낙관적 UI). 표준 UX.
- **폼류**(displayName, bio): "Save" 버튼.
- 토글 실패 시 원복 + 토스트.

### 재검토 트리거
- 항목이 더 늘어 탭 7개가 빽빽해질 때 → 카테고리 재정렬(Notion 의 "Settings & members" 처럼 모달로 옮기는 옵션도).
- B2B 워크스페이스 ownership 이 강해져 워크스페이스 설정이 비대해질 때 → 워크스페이스 settings 페이지를 별도 IA(`/workspaces/{id}/settings`) 로 분리.
- 알림 채널이 4종(+Slack/Teams) 이상으로 늘 때 → 매트릭스 UI 한계, "subscription" 모델로 재설계.

### 관련 코드 (Phase 1 기준)
구현 예정:
- `iam-api/auth/domain/model/User.kt` — preferences 필드(`language`/`timezone`/`theme`/`defaultWorkspaceId`).
- `iam-api/auth/domain/model/vo/Theme.kt` (LIGHT/DARK/SYSTEM enum).
- `iam-api/auth/infrastructure/jpa/entity/UserEntity.kt` — 4 컬럼.
- `iam-api/auth/application/usecase/UpdateMyProfileUseCase.kt`, `GetMyProfileUseCase.kt` — 확장.
- `iam-api/auth/application/usecase/GetPublicProfileUseCase.kt` (신설), `ListMyConnectionsUseCase.kt` (신설).
- `iam-api/auth/presentation/web/UserController.kt` — `GET /users/{id}`, `GET /me/connections` 추가.
- `iam-api/auth/application/port/SocialIdentityRepositoryPort.kt` — `findByUserId(userId)` 추가.

---

## 6. 홈의 "오늘의 키워드" — 정의·가공·시각화·서비스 분리

*기록: 2026-05-30 · 상태: **미구현 — 설계 등재**. 별도 `:insight-api` 로 분리 확정.*

홈 화면 진입 시 사용자가 보는 "오늘의 키워드" 섹션. 네이버 실시간 검색어 같은 카드형 노출이 컨셉이지만, *우리 서비스의 가치 제안* 과 결합되도록 **내부 사용자 데이터를 source 로 한 derived read model** 로 구축한다. 이 문서는 (a) 키워드의 정의 · (b) source 선택 근거 · (c) 가공 파이프라인 · (d) UI 시각화 · (e) 데이터 모델 · (f) 마이크로서비스 분리 정책 · (g) 단계적 도입 을 한 곳에 모아 추후 구현·재검토의 기준점으로 둔다.

### 6.1 "키워드" 의 정의 — 무엇을 셀까

| 후보 | 정의 | 신호 강함 | 시스템 자산 |
|---|---|---|---|
| **(a) Exception Class** | `NullPointerException` 등 simple name | ★★ 너무 광범위 | ✅ `snapshot.exception_class` |
| **(b) Fingerprint Cluster** | 같은 fingerprint(=같은 본질의 에러)에 N명이 누적 | ★★★★ 가장 강함 | ✅ `fingerprint` 인덱스 |
| **(c) Title/insight 토큰** | "useEffect 무한 호출" 같은 명사구 | ★★ 어휘 다양·노이즈 | 토큰화·불용어 필요 |
| **(d) 사용자 검색 쿼리** | search 탭의 쿼리 빈도 | ★★★★★ 의도 직접 | ❌ 검색 미구현 |
| **(e) 라이브러리/툴 태그** | `spring`/`react`/`postgres` 정규화 키 | ★★★ 분류 명확 | 라이브러리 사전 매핑 필요 |

채택: **(b) + (a) + (e)** 부터 시작. (d) 는 검색 도입 후, (c) 는 정규화 비용 큼.

### 6.2 Source — 내부 vs 외부 크롤링

| | **내부 (사용자 데이터) ⭐ 채택** | 외부 (IT 트렌드 크롤링) |
|---|---|---|
| 신호 정체 | "이 서비스 사용자가 *지금 어떤 에러로 고생 중*" | "전 세계 개발자 관심사" |
| 다른 곳에서 못 구함 | ✅ 우리만의 자산 | ❌ HN/GitHub/SO 미러 |
| 차별화 | ★★★★★ | ★ |
| 콜드 스타트 | ❌ 비어있음 | ✅ |
| 운영 비용 | 0 | 크롤러+TOS+저작권+다국어 |
| 사용자 동선 | 클릭 → 우리 케이스 (잔류) | 클릭 → 외부 (이탈) |

**핵심 통찰**: 외부 크롤링은 "에러 아카이브" 라는 서비스 정체성을 깎는다. 콜드 스타트는 **편집자 픽 시드 + 워크스페이스 스코프 + fingerprint 임계 ≥2** 의 셋으로 풀고, 외부 데이터는 *우리 데이터와 교차할 때만 사이드 신호로* 후반에 보조 사용.

### 6.3 의미 있는 키워드의 3축

```
"NullPointerException 42건"  →  ❌ 항상 1위, 액션 불가
"Spring JPA LazyInit"        →  ✅ 구체 + 5명 해결 중 + 어제 4→오늘 12
"React useEffect 무한루프"   →  ✅ 트렌딩 + 1 solution
```

키워드 1개의 가치 = **구체성(specificity) + 트렌딩(velocity) + 해결가능성(solvability)** 3축. 단순 카운트는 ①번만이라 *영원한 1등 NPE* 문제. 3축을 모두 반영하는 score 가 핵심.

### 6.4 가공 파이프라인 — 5단계

```
원시(error_case + step + solution)
   │
   ├─ ① 추출    exception class · stack frame(user-code only) · title 토큰 ·
   │             fingerprint · library 사전 매핑 · meta.environment
   │
   ├─ ② 정규화  숫자/UUID 마스킹 · 한국어/영어 동의어 사전 ·
   │             대소문자·공백·하이픈 통일 (search 의 normalize 와 공유)
   │
   ├─ ③ 집계    [시간 × 키워드 종류 × 스코프] 3D 큐브
   │             per keyword: case_count · distinct_users · distinct_wss ·
   │             resolved_count · solution_count · step_count · last_seen_at
   │
   ├─ ④ 랭킹    score = log(case_count+1)
   │                   * velocity            (today/avg_7d)
   │                   * distinct_user_factor (어뷰징 억제)
   │                   * (1 + resolved_rate)  (해결 가산)
   │                   / idf_penalty          (영원한 1등 억제)
   │
   └─ ⑤ 표시    카드 6섹션 (§6.6)
```

집계는 **`distinct_users`/`distinct_wss`** 까지 두는 게 핵심 — 한 사용자가 같은 에러를 N개 등록해 1위 만드는 어뷰징 차단.

### 6.5 집계 차원 — 3D 큐브

```
[키워드 종류]  EXCEPTION_CLASS / FINGERPRINT_CLUSTER / LIBRARY_TAG / USER_QUERY(후)
      ×
[시간 윈도우]  HOUR / DAY / WEEK
      ×
[스코프]      GLOBAL_PUBLIC / MY_WORKSPACE / FOLLOWING(후)
```

홈 카드 섹션마다 다른 슬라이스를 노출. *Phase 1 의 콜드 스타트* 에서는 `MY_WORKSPACE` 스코프(글로벌 데이터 없어도 의미) + 임계 ≥2 클러스터 + 편집자 픽으로 시작.

### 6.6 UI 시각화 — 홈 화면 6섹션

```
🔥 오늘의 핫 에러            (24h, FINGERPRINT/EXCEPTION 합산, top 6)
   카드 정보 단위:
      • 키워드(구체 라벨)             "Spring JPA — LazyInitializationException"
      • 카테고리 태그                  🏷 spring · jpa
      • 분포                          📊 12 케이스 · 8명 · 3 워크스페이스
      • 해결가능성 시그널              🎯 활성 step 7 · ✨ 2 solution · 🟢 5 RESOLVED
      • 트렌딩(있을 때만)              ↑ 어제 4 → 오늘 12 (×3.0)
      • 최근 등록 시각 · [살펴보기] · [follow 키워드]

📈 급상승                      (velocity desc)
🎯 사람들이 지금 해결 중        (IN_PROGRESS · 최근 step ≤1h)
🏆 오늘 해결됐어요              (오늘 RESOLVED 전환)
🔧 라이브러리/툴 (7d)            spring · react · postgres · jackson · kotlin (chip)
✨ 편집자 픽                   (수동 큐레이션, 콜드 스타트엔 메인)
```

각 카드 클릭 → 키워드 상세 페이지로 라우팅(§6.7).

### 6.7 키워드 상세 페이지

```
←  Spring JPA — LazyInitializationException                       [follow]
═══════════════════════════════════════════════════════════════════════════
📊 분포                            📈 7일 추이
  12 케이스 · 8명 · 3 ws            ▁▁▂▂▃▆▇  (오늘 ↑)
  5 RESOLVED / 7 IN_PROGRESS

🎯 사람들이 시도한 접근 (step.attemptType 분포)
  CODE_CHANGE ████████████ 60%   CONFIG ██████ 30%   ROLLBACK ██ 10%

✨ 게시된 해결법 (2)
  1. "OSIV=false + Fetch Join"  by @kim · 적용 8건
  2. "DTO 변환 시점을 트랜잭션 안으로"  by @lee · 적용 4건

📋 관련 케이스 (12)  · 🔗 같은 fingerprint(들)
```

키워드 자체가 "이 에러의 모든 것" 진입점이 되도록 *접근 분포·해결법·관련 케이스* 까지 한 페이지에. 검색이 없어도 키워드만으로 가치 발생.

### 6.8 우선순위 — 콜드 스타트 시기 *진짜 보여줄 3섹션*

```
필수 (Phase 1)                          최소 데이터 요건
  ✨ 편집자 픽                          → 0건 (운영자 시드)
  🎯 사람들이 지금 해결 중              → fingerprint cluster ≥ 2 만 채워도 됨
  🏆 오늘 해결됐어요                    → 오늘 RESOLVED 1건 이상

다음 (Phase 2 — 사용자 수십~수백)
  🔥 오늘의 핫 에러   🔧 라이브러리/툴

성숙 (Phase 3)
  📈 급상승 (7d baseline 필요)   USER_QUERY(검색 도입 후)
```

글로벌 통계 없이도 첫 화면이 *우리 도메인 콘텐츠* 로 채워지는 게 핵심.

### 6.9 데이터 모델

```sql
-- 매 10분 갱신되는 키워드 스냅샷
CREATE TABLE hot_keyword_snapshot (
  id              BIGSERIAL PRIMARY KEY,
  window_kind     VARCHAR(8)   NOT NULL,   -- HOUR / DAY / WEEK
  window_start    TIMESTAMP    NOT NULL,
  scope_kind      VARCHAR(16)  NOT NULL,   -- GLOBAL_PUBLIC / WORKSPACE / FOLLOWING
  scope_key       BIGINT,                  -- workspaceId 등 (GLOBAL 은 NULL)
  source          VARCHAR(20)  NOT NULL,   -- EXCEPTION / FINGERPRINT / LIBRARY_TAG
  keyword_key     VARCHAR(255) NOT NULL,   -- 정규화 키(예: "spring:lazy-init")
  display_label   VARCHAR(255) NOT NULL,
  rank            INT NOT NULL,
  case_count      INT NOT NULL,
  distinct_users  INT NOT NULL,
  distinct_wss    INT NOT NULL,
  resolved_count  INT NOT NULL,
  solution_count  INT NOT NULL,
  step_count      INT NOT NULL,
  velocity        DOUBLE PRECISION,
  score           DOUBLE PRECISION NOT NULL,
  computed_at     TIMESTAMP NOT NULL
);
CREATE INDEX ix_hot_lookup
  ON hot_keyword_snapshot(window_kind, window_start, scope_kind, scope_key, source, rank);

-- 편집자 픽 (콜드 스타트 메인 + 상시 유지)
CREATE TABLE hot_keyword_editor_pick (
  id              BIGSERIAL PRIMARY KEY,
  pick_date       DATE NOT NULL,
  display_order   INT NOT NULL,
  title           VARCHAR(255) NOT NULL,
  subtitle        VARCHAR(255),
  href_keyword_key VARCHAR(255),
  href_case_id    BIGINT,
  created_by      BIGINT NOT NULL,
  created_at      TIMESTAMP NOT NULL,
  UNIQUE(pick_date, display_order)
);
```

테이블은 **`:insight-api` 의 별도 schema 또는 별도 DB**. core-api 의 OLTP 와 분리.

### 6.10 마이크로서비스 — `:insight-api` 활성화 (정정)

이전 분석에서 "core-api 안 sub-module" 권고했으나 **정정**: monorepo 에 이미 예약된 `:insight-api` 빈 스텁이 있고, BC 경계(derived read model)가 명확하며, 홈은 진입점이라 OLTP 격리 ROI 가 큼 → **처음부터 `:insight-api` 로 분리**.

| 결정 | 채택 |
|---|---|
| 서비스 분리 | ✅ `:insight-api` 활성화 |
| 동기화 방식 | **HTTP polling** (core-api 의 internal API 호출). Phase 2 부터 outbox 검토 |
| 저장 | insight-api 의 별도 schema/DB |
| 인증 | 기존 internal JWT (RS256, aud=insight-api) — gateway/core-api 발급키 재사용 |
| 게이트웨이 라우트 | `/api/v1/home/keywords` → insight-api, `/api/v1/admin/editor-picks` → insight-api |

#### core-api 가 노출할 internal API (insight-api 가 호출)
```
GET /internal/insights/case-events?since=<cursor>&limit=200
   ⤷ case·step·solution 변경 이벤트 페이지(증분).
   ⤷ 각 row: { caseId, eventType, occurredAt, visibility,
                exceptionClass, fingerprint, libraryTags?, ... }
   ⤷ 인증: X-Internal-Auth (RS256, iss=gateway 또는 insight-api)
   ⤷ 응답에 다음 cursor 포함
```

대안 (채택 안 함):
- **공유 DB read** — BC 위반. 장애 격리 X.
- **CDC/Debezium + Kafka** — 정석이나 인프라 무거움. Phase 후반.
- **outbox + polling** (Kafka 없이) — Phase 2 에서 신선도 요구 커지면 도입.

### 6.11 search/matching 과의 관계 — Discovery 통합

`:insight-api` 가 활성화되면 *search* (검색 분석)과 *matching* ([§4](#4-solution-의-템플릿화--재사용--매칭)) 도 같은 자리에 묶이는 게 자연스럽다 — 셋 다 derived read model.

옵션:
- 이름을 **`insight-api` 유지**하고 내부 `insight/` + `search/` + `matching/` sub-module.
- 또는 **`discovery-api`** 같은 broader 이름으로 리네임.

지금은 `insight-api` 로 시작, search/matching 도입 시점에 리네임 검토. **셋의 BC 분리는 *함께 떠난다*** 가 원칙 — 셋이 흩어지면 같은 read 인프라(인덱스·캐시·임베딩)를 중복 운영하게 됨.

### 6.12 단계적 도입

```
Phase 1  (지금 콜드 스타트)
   :insight-api scaffolding (build.gradle / Application / Health / internal-auth)
   + Postgres 집계 테이블 + 편집자 픽 테이블
   + core-api 의 GET /internal/insights/case-events
   + 10분 스케줄러 (HTTP polling)
   + 3섹션 노출: 편집자 픽 / 지금 해결 중 / 오늘 해결됨
   + gateway 라우트 1개 (/api/v1/home/keywords)

Phase 2  (사용자 수십~수백)
   + Caffeine 인-메모리 캐시 (TTL 10분 → 99% cache hit)
   + 7일 baseline → velocity 섹션 추가
   + LIBRARY_TAG source 추가 (라이브러리 사전 도입)

Phase 3  (search/matching 합류)
   + search 통합 (FTS or Meilisearch)
   + matching 통합 (fingerprint 유사도 + 임베딩)
   + 필요 시 :discovery-api 로 리네임
   + outbox/CDC 로 동기화 정석화
```

### 6.13 함정·운영 고려

- **권한 누수**: 모든 집계는 `visibility=PUBLIC` 인 케이스만. WORKSPACE 스코프는 멤버에게만. insight-api 도 권한 필터 한 번 더.
- **어뷰징(자가증폭)**: `distinct_users` 분모로 가산 — 한 사용자가 같은 키워드에 contribution cap.
- **콜드 스타트**: 편집자 픽으로 메우고, fingerprint 클러스터 임계 ≥2 로 시작(기본 ≥3 보다 완화).
- **편향(영원한 1등)**: idf 보정 + velocity 섹션으로 보완 + 카테고리 별 분리.
- **신선도 vs 부하**: polling 10분 + 캐시 TTL 10분이 기본. 사용자가 등록 직후 자기 데이터 못 보면 어색하므로 *내 워크스페이스 카드만* TTL 1분 고려.
- **편집자 role**: 현재 사용자 role 모델 없음 → Phase 1 의 편집자 픽 admin 권한은 *고정 user_id whitelist* 로 잠정 시작(설정값).

### 6.14 재검토 트리거

- **편집자 픽이 더 이상 메인이 아닐 정도로** 사용자 데이터가 쌓일 때 → Phase 2 활성화.
- **홈 응답시간이 100ms 를 넘어가기 시작할 때** → Caffeine 캐시 도입.
- **신선도 요구가 polling 으로 부족할 때**(등록 직후 키워드 반영 기대) → outbox/CDC 검토.
- **search/matching 이 본격 도입될 때** → :discovery-api 통합 또는 리네임.
- **LIBRARY_TAG 사전 유지비가 큼** → 임베딩으로 대체 검토.
- **편집자 픽에 운영자 role 시스템 필요** → 사용자 role 모델 도입과 함께.

### 6.15 관련 코드

활용 가능 (이미 존재):
- `core-api/errorcase/case/domain/model/vo/Fingerprint`, `shared/util/FingerprintGenerator` — 핵심 키워드 source.
- `core-api/errorcase/case/infrastructure/jpa/entity/ErrorCaseEntity.snapshot.fingerprint/exception_class` — 인덱스 있음.
- `gateway/config/RouteConfig` — `/api/v1/home/keywords` 라우트 추가 자리.
- `shared-internal-auth/` — RS256 검증·발급 라이브러리.
- `iam-api/auth/infrastructure/security/InternalTokenResourceServerConfig` — insight-api 가 동일 패턴 적용.

신설 예정 (Phase 1):
- `:insight-api` 모듈 자체 (`build.gradle.kts`, `InsightApiApplication`, `application.yml`).
- `insight-api/application/usecase/AggregateHotKeywordsUseCase`, `GetHomeKeywordsUseCase`, `EditorPickAdminUseCase`.
- `insight-api/domain/model/HotKeyword`, `EditorPick` (애그리거트 X — derived read model).
- `insight-api/infrastructure/corerest/CoreApiCaseEventClient` (RestClient + internal JWT 발급).
- `insight-api/infrastructure/schedule/AggregationScheduler` (`@Scheduled` + ShedLock).
- `insight-api/presentation/web/HomeKeywordsController`, `EditorPickController`.
- core-api 측: `case/application/usecase/StreamCaseEventsUseCase`, `presentation/web/InternalInsightsController` (internal 전용 라우트).

> 참고: §검색 분석(미등재, in-conversation)과 §4 Solution 매칭은 본 §6 과 같은 BC 분리 트리거 그룹(Discovery)을 공유한다. 셋 중 어느 것이 먼저 도입되든 *insight-api 가 그 집의 첫 거주자* 가 된다.

---

## 7. 댓글(Comment) 시스템

*기록: 2026-05-31 · 갱신: 2026-06-01 · 상태: **✅ 구현 완료**.*

> 모델·정책·FE/BE 역할·통신 흐름·기능 인벤토리는 [`comment-discussion.md`](comment-discussion.md) 종합 문서 참조. 본 §7 은 *대안과 채택 안 한 이유* 만 회고용으로 남긴다.

> **2026-06-01 변경**: 인용 종류를 4종(NONE/CASE_BODY/STEP/**CODE_BLOCK**) → **3종**(NONE/CASE_BODY/STEP) 으로 좁힘. 본문에 임베드된 `@snippet(id)`·코드블록은 *본문의 일부* 로 보고 CASE_BODY 에 흡수. "어느 스니펫" 위치 점프는 FE 가 `quoteSnapshot` 첫 매치 검색으로 충분. 잃는 것 (`quoteSummary.snippets[]` 카운트) 보다 얻는 것 (enum·검증 분기·DB CHECK·filter·**`CodeSnippetRepositoryPort.findById(Long)` 롤백** — 모듈 의존 그래프 단순화) 이 큼. DB 점검 0건. 결정 회고는 [`comment-discussion.md`](comment-discussion.md) §9-B.

> **2026-06-01 기능 추가**: **GitHub Suggest 차용 Diff 제안 댓글**. 코드블럭 gutter 드래그 → 라인 범위 + after 코드 → 댓글에 첨부. 댓글 1:1 `CommentSuggestion` 엔티티 신설(commentId UNIQUE). sourceType (SNIPPET / MARKDOWN_CODE), status (PENDING / APPLIED / REJECTED). **sourceId 는 opaque** — *제안이지 반영 보장 아님* 이라 BE 검증 X(snippet→comment 의존성 다시 들이지 않음). **상태 변경 권한은 케이스 owner 만** (PR author 와 동치). 자동 코드 반영 X (Phase 1) — 상태 표시만. diff lines 는 BE 응답 시 단순 line-단위 unified diff(`CommentSuggestion.toDto()`) 로 생성. 종합은 [`comment-discussion.md`](comment-discussion.md) §11.

에러케이스 상세 하단의 댓글 섹션. 사용자가 본문/step 을 인용하거나 자유롭게 댓글을 달 수 있고, 케이스 작성자가 "이 댓글이 도움됐다" 마크로 신뢰 시그널을 남길 수 있다. 토론을 step 으로 승격할지·댓글로 남길지의 경계가 핵심 설계 지점.

### 현재 구현
미구현. 본 항목은 합의된 설계의 등재.

### 의도/근거
- 에러 해결의 **토론·맥락 정보** 는 step 으로 승격할 만큼 정형화되지 않는 "잡담·교차 의견·짧은 노하우" 가 다수. 이를 step 으로 강제하면 노이즈가 끼고, 본문/step 안에 끼워넣으면 작성자 관점이 흐려진다. → 별도 댓글 채널이 필요.
- step/solution 과 달리 **타임라인의 진실 기록이 아닌 토론 메타정보** 라 권한·구조 모두 더 가벼움.

### 데이터 모델 — `errorcase/comment/` sub-module (애그리거트 신설)

```kotlin
class Comment(
   id, errorCaseId, authorUserId,
   parentCommentId: Long?,              // depth=1 답글 (§ "답글 정책" 참조)
   body: String,                        // 자유 마크다운, @snippet/@attach 임베드 가능
   quoteSourceKind: QuoteSourceKind,    // NONE / CASE_BODY / STEP
   quoteSourceId: Long?,                // STEP 일 때 step id
   quoteSnapshot: String?,              // 인용 *시점* 텍스트 (원본 수정·삭제돼도 유지)
   isHelpful: Boolean = false,          // 케이스 작성자가 ✅ 마킹
   editedAt: Instant?,                  // 수정 시 set → "(편집됨)" 표시
   deletedAt: Instant?,                 // soft delete (답글 placeholder 유지)
   createdAt, updatedAt
)

enum class QuoteSourceKind { NONE, CASE_BODY, STEP }

class CommentReaction(commentId, userId, emoji)         // 이모지 (한 유저당 같은 이모지 1회)
class CommentMention(commentId, mentionedUserId)         // @멘션 — 알림 라우팅용
```

테이블: `error_case_comment` · `error_case_comment_reaction` · `error_case_comment_mention`.

### 핵심 정책

| 축 | 결정 |
|---|---|
| **표시 위치** | 에러케이스 상세 화면 하단 섹션 |
| **인용 유형** | 일반(NONE) · 본문 인용(CASE_BODY) · step 인용(STEP) — 인용 출처는 `[본문]` / `[Step N]` 태그로 카드에 표시 |
| **인용 자동화 UX** | 본문/step 영역에서 **텍스트 드래그 → "인용 댓글 달기"** → 프런트가 드래그 origin(DOM context)으로 `quoteSourceKind`·`quoteSourceId` 자동 채움. 사용자는 클릭만 |
| **인용 snapshot** | `quoteSnapshot` 에 인용 시점 텍스트 저장. 원본 수정 시 카드에 *"⚠️ 인용 후 수정됨 [현재 보기]"* 표시 (원본 fetch 결과와 snapshot 비교) |
| **본문 마크다운** | 자유 마크다운 + 코드블록(``` ```) + `@snippet(markerId)`/`@attach(markerId)` 임베드(이미 케이스에 연결된 marker 만) |
| **답글** | **depth = 1** — 답글의 답글은 같은 줄기에 평탄하게(Instagram/YouTube 패턴). 더 깊은 토론은 *step 으로 승격* 이 도메인 의도. 서버에서 `parentCommentId` 가 답글이면 자동으로 *부모의 부모*(top-level)로 redirect |
| **역할 배지** | `[작성자]`(case owner) · `[ADMIN]`(ws ADMIN) · `[기여자]`(그 케이스에 step 작성 이력 있음) · `[해결자]`(자신의 댓글이 ✅ isHelpful 표시 받음) |
| **시각 표시** | 상대시간(`3시간 전`) 기본, hover 시 절대시간(ISO) |
| **이모지 리액션** | `CommentReaction(commentId, userId, emoji)` — 한 유저당 같은 이모지 1회. UNIQUE(commentId, userId, emoji) |
| **도움됨 마크** | `isHelpful` 단일 컬럼. **케이스 owner 만 토글 가능**. 여러 댓글 동시 ✅ 허용(Stack Overflow accepted answer 보다 약한 신호) |
| **@멘션** | 본문 파싱 → `CommentMention` 적재 → noti-api Phase 3 알림 라우팅 |
| **수정/삭제** | 본인만. **soft delete**(`deletedAt`) — 답글이 달린 댓글이 사라지면 `"[삭제된 댓글]"` placeholder 로 컨텍스트 보존. 케이스 owner / ws ADMIN 은 **숨김(hide)** 만 가능(검열 사고 방지) |
| **권한 (작성·조회)** | `ErrorCaseAccess.requireRead` 와 동일 — 댓글은 토론 도구라 READ 권한자(워크스페이스 READ 멤버 포함)도 작성 가능. step/solution 의 WRITE+ 와 다름 |
| **정렬** | 기본 최신순(`createdAt DESC`) · 옵션: 오래된순(흐름 읽기) / 도움됨 우선(✅ 댓글 상단 고정) |
| **페이징** | top-level cursor(`createdAt|id`). 답글은 한 부모의 모든 답글을 함께 반환(개수 보통 작음) |
| **응답 그루핑** | top-level + replies 트리. 평면 리스트가 아닌 *부모-자식 묶음* 으로 backend 가 그루핑 |

### UX 자동화 — 드래그→인용 (강력 권고)

```
┌─[ 케이스 본문 ]──────────────────────────────────────┐
│  원인은 캐시 만료 처리. @snippet(7a)                   │   ← 사용자가 일부를 드래그
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                       │
│  [💬 인용 댓글 달기]   ← 드래그 직후 toolbar 등장      │
└──────────────────────────────────────────────────────┘
                  │ 클릭
                  ▼
   POST /comments {
     body: "...",
     quoteSourceKind: "CASE_BODY",   ← 프런트가 DOM context 로 자동 판정
     quoteSourceId: null,
     quoteSnapshot: "원인은 캐시 만료 처리."   ← 드래그 텍스트 그대로
   }
```

- step 영역에서 드래그하면 `quoteSourceKind: "STEP"` + `quoteSourceId: 3` 자동.
- 백엔드는 *추가 모델 변경 없이* 이미 정의된 필드에 그대로 수신. UI 마찰 ↓.

### 엔드포인트 (예정)

```
POST   /api/v1/error-cases/{caseId}/comments
GET    /api/v1/error-cases/{caseId}/comments?sort=newest|oldest|helpful&cursor=&size=
PATCH  /api/v1/error-cases/{caseId}/comments/{commentId}          ← 본인만, body 수정
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}          ← 본인만, soft delete

POST   /api/v1/error-cases/{caseId}/comments/{commentId}/helpful  ← case owner only, toggle
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}/helpful

POST   /api/v1/error-cases/{caseId}/comments/{commentId}/reactions { emoji }
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}/reactions/{emoji}
```

응답 DTO 의 트리 형태:
```json
{
  "items": [
    {
      "id": 1, "parentCommentId": null, "body": "...", "isHelpful": true,
      "quote": { "kind": "STEP", "stepId": 3, "snapshot": "...", "isStale": false },
      "author": { "userId": 5, "isAuthorOfCase": true },
      "reactions": [{"emoji":"👍","count":3,"reactedByMe":false}],
      "replies": [
        { "id": 2, "parentCommentId": 1, "body": "...", "author": {...} }
      ],
      "createdAt": "...", "editedAt": null, "deletedAt": null
    }
  ],
  "nextCursor": "...",
  "hasNext": false
}
```

### 다른 선택지 (채택 안 함)

| 옵션 | 채택 안 한 이유 |
|---|---|
| 댓글을 step 안에 두기(step.comments) | step 외 본문 인용·전반 토론 표현 불가. 도메인 의미 흐림 |
| depth=∞ 무한 스레드(Reddit 식) | 들여쓰기 지옥·모바일 부적합. 더 깊은 토론은 step 승격이 더 자연 |
| depth=0 평면만(GitHub Issue) | 답글 누구한테 한 건지 멘션으로만 추측 — 흐름 추적 어려움 |
| 댓글에 자체 스니펫·첨부 모델 | 케이스의 marker 임베드로 충분. 모델 폭증 회피 |
| 도움됨 = "accepted answer" 단일 | 여러 해결 경로가 있을 수 있어 *복수 허용* 이 도메인에 맞음 |

### 함정·재검토 트리거

- **인용 후 원본 수정·삭제**: snapshot 으로 잡지만, 원본을 fetch 해서 다르면 *stale* 표시. UI 가 그 비교 안 하면 사용자는 모름 → 응답에 `quote.isStale` 플래그 미리 계산해 내려주기.
- **답글 깊이 강제**: 사용자가 *답글에 답글* 누르면 프런트도 모르고 서버도 부모의 부모로 redirect. 두 곳에서 같은 규칙 보장 필요.
- **삭제된 댓글 placeholder**: 답글이 없으면 그냥 hard hide 하고 싶을 수 있지만, **있는지 없는지 응답 build 전엔 모름** → soft delete 가 단순.
- **이모지 어뷰징**: 한 사용자가 같은 이모지 여러 번 → UNIQUE 제약으로 막힘. 다른 사용자 여러 명이 1회씩은 자연스러움.
- **재검토 트리거**: ① 토론이 너무 길어 들여쓰기 한 단으로 부족하다는 피드백 → depth=2 검토. ② 댓글이 트래픽 압도 → 별도 BC(`discussion`) 검토. ③ 모더레이션 사고 → 운영자 role + report/hide 워크플로.

### 확장 포인트

- **알림 연동** (Phase 3 IA §5): 멘션·내 케이스 댓글·내 댓글에 답글 → noti-api 라우팅.
- **인용 검색 신호**: `quoteSourceId=stepX` 가 많은 step 은 *논의가 활발한 step* 으로 매칭(§4) 가중치.
- **자동 매칭 추천**: 같은 fingerprint 케이스의 ✅ 도움됨 댓글을 신뢰 시그널로 §4 Phase 1 매칭에 노출.
- **댓글에서 step 으로 승격** 액션: 활발한 댓글 토론을 *step 으로 변환* (작성자/owner 만). 일종의 "댓글 → step 채택"; 댓글 본문이 새 step 의 body 로 복사.

### 관련 코드 (예정)

신설 sub-module:
```
core-api/errorcase/comment/
  domain/model/Comment.kt
  domain/model/vo/QuoteSourceKind.kt
  application/command/*.kt
  application/port/CommentRepositoryPort.kt
  application/usecase/{Create,List,Update,Delete,Helpful,Reaction}*UseCase.kt
  presentation/web/CommentController.kt
  presentation/web/dto/{request,response}/*.kt
  infrastructure/jpa/CommentJpaRepository.kt
  infrastructure/jpa/adapter/CommentRepositoryAdapter.kt
  infrastructure/jpa/entity/{Comment,CommentReaction,CommentMention}Entity.kt
```
재사용: `shared/application/usecase/ErrorCaseAccess` (권한 헬퍼).

---

## 8. 댓글 BC 위치 — `errorcase` sub-module vs 별도 BC vs 별도 서비스

*기록: 2026-05-31 · 상태: **현재 `errorcase/comment/` sub-module 유지**. 분리 트리거 등재.*

### 배경

§7 에서 댓글 모델·정책을 확정하고 구현까지 마쳤다(`comment-discussion.md`). 그 다음 떠오르는 질문: **마이크로서비스/DDD 관점에서 comment 가 진짜 core-api(errorcase BC) 안에 있어야 맞나?**

§4(Solution 매칭)·§6(insight-api / 키워드 서비스) 와 같은 *BC 분리 트리거 그룹* 안에서 함께 다뤄야 미래 결정이 정합.

### DDD 5 잣대로 평가

| 잣대 | comment 의 답 | 같은 BC 신호 | 다른 BC 신호 |
|---|---|---|---|
| **유비쿼터스 언어** | "댓글·답글·인용·리액션·도움됨·멘션" — errorcase 의 "에러·해결·step·solution" 과 *다른 의미장* | △ | △ |
| **모델 결합** | `errorCaseId` FK · 인용 source(case/step/snippet) · 권한 `ErrorCaseAccess` 상속 · cascade 삭제 | ✅ | |
| **트랜잭션 경계** | 인용 source 검증·cascade 가 같은 DB·같은 txn | ✅ | |
| **사용자 여정** | 케이스를 *읽다가* 토론을 다는 단일 흐름 | ✅ | |
| **변경 축 / 트래픽** | comment 가 case 본문보다 훨씬 자주 read+write — 패턴 불일치 | | △ |
| **다른 도메인 재사용성** | step/solution/공개 블로그/프로필 등에도 같은 모델이 자연 | | △ |

5개 잣대 중 **4개가 같은 BC 신호, 2개(변경 축·재사용성) 가 약한 다른 BC 신호** → 현재 한 BC 가 합리적, *재사용성과 트래픽 두 축이 미래 분리의 씨앗*.

### Search(§검색)·Solution 매칭(§4) 과의 비교 — comment 만 *진짜 애그리거트*

| | Search | Solution 매칭(§4) | **Comment** |
|---|---|---|---|
| 자체 식별자 | ❌ (인덱스만) | ❌ (파생) | ✅ id, body, lifecycle |
| 자기 invariants | ❌ | ❌ | ✅ depth=1, 본인만 수정/삭제, UNIQUE 등 |
| 자기 lifecycle | ❌ derived | ❌ derived | ✅ 작성·편집·삭제 |
| Authoritative state | ❌ (case 가 진실) | ❌ | ✅ 자기 진실원천 |
| 유형 | **derived read model** | **derived read model** | **진짜 애그리거트** |
| BC 분리 시 어디로 | Discovery / Insight | Discovery / Insight | **Discussion (별도 BC 가능)** |

→ Search·매칭은 derived 라 *모델만 분리* 하는 것도 의미가 있는 반면, comment 는 자체 애그리거트라 **분리한다면 진짜 별도 BC 가 됨**. 단 그 분리 정당성은 *지금 약하다*.

### 분리 옵션 3가지

| 옵션 | 위치 | 구현 비용 | 적합 시점 |
|---|---|---|---|
| **A. 현재** — `errorcase/comment/` sub-module | core-api 안, errorcase BC 의 sub | 0 | **지금** |
| **B. 같은 서비스 안 별도 BC** — `core-api/discussion/` | core-api 안, BC 분리 | 작음 (폴더 이동 + ACL 클래스) | 다른 도메인(step·solution·blog 등)에도 댓글 필요해질 때 |
| **C. 별도 마이크로서비스** — `discussion-api` | 새 서비스 | 큼 (배포·DB·이벤트/RPC) | 트래픽 폭증 + 모더레이션 별도 팀 + 외부 SLA |

### 분리 트리거 (미리 정의)

| # | 트리거 | → 가는 옵션 |
|---|---|---|
| 1 | **step·solution·블로그(publish-api)·공개 프로필에도 댓글이 필요** — comment 모델이 errorcase 종속 X 로 일반화돼야 할 때 | **A → B** |
| 2 | **모더레이션 정책 비대화** — 신고/자동검열/role 기반 hide 가 본격화돼 errorcase 와 다른 lifecycle 가짐 | A → B |
| 3 | **트래픽 비대칭** — 댓글 read 가 케이스 OLTP 의 2~3배 + DB 부하 분리 정당화 | B → C |
| 4 | **알림(noti-api)/소셜그래프 와의 결합 폭증** — 멘션 fan-out, 답글 알림, 리액션 푸시 등 — discussion 이 noti 와 더 가까워질 때 | B → C |
| 5 | **외부 컨슈머용 댓글 API** — 공개 SDK / 위젯 형태 | C |

→ **트리거 #1 이 가장 가능성 높은 신호**. step body 안의 토론, solution 위의 토론, public 케이스 카탈로그의 토론 등이 자연스러운 확장 경로.

### 분리 시 풀어야 할 결합

| 결합 | 분리 시 풀이 |
|---|---|
| 인용 source 검증 (STEP 이 같은 케이스 소속) | **ACL(Anti-Corruption Layer)** — `discussion` 이 `errorcase` 의 *읽기 전용 query port* 로 조회. 또는 검증을 자기 책임 X 로 약화 |
| 권한 (`ErrorCaseAccess.requireRead`) | 같은 ACL — "이 case 에 대한 내 권한" 만 외부 조회 |
| 케이스 삭제 cascade | **이벤트** — `CaseDeleted` 발행 → discussion 이 자기 댓글 정리 |
| `isAuthorOfCase` 계산 | case ownerUserId 를 ACL 로 조회 |

→ 옵션 B(같은 서비스)는 ACL 클래스 한두 개. 옵션 C(별도 서비스)는 이벤트 인프라(outbox/Kafka) 필요.

### 권고 — 지금은 A, 분리 친화 코드 형태는 *이미* 갖춰져 있음

| 시점 | 행동 |
|---|---|
| **지금** | A 유지. 분리 트리거 모니터링. |
| 트리거 #1 도달 | A → **B** — `core-api/discussion/` 폴더 이동 + ACL. 1-2시간 작업. |
| 트리거 #3·#4 도달 | B → **C** — `discussion-api` 별도 서비스 + outbox/이벤트. §6 insight-api 와 분리 인프라 공유 가능. |

**현재 코드가 이미 분리 친화적**(굳이 지금 분리할 필요 없는 이유):
- comment 가 case/step/snippet 에 **포트 인터페이스로만** 의존 (`ErrorCaseRepositoryPort`, `StepRepositoryPort`, `CodeSnippetRepositoryPort`) — 분리 시 ACL 로 갈아끼우기만 하면 됨.
- 도메인 객체에 `errorCaseId` 가 *그냥 Long* — FK 가 도메인에 직접 노출되지 않음.
- 권한 체크가 `ErrorCaseAccess` 라는 *공유 객체* 를 통해서 — 분리 시 ACL 한 곳만 교체.

### 채택하지 않은 안 — 즉시 별도 BC / 별도 서비스 분리

- **즉시 옵션 B (지금 BC 분리)**: 트리거 도달 전엔 *과설계*. comment 모든 의미가 *현재* errorcase 컨텍스트 안에서만 동작 — 다른 도메인에 댓글 X. cascade·인용 검증·권한이 한 BC 안에서 단순. 미래 분리도 폴더 이동만으로 충분해 *지금 분리 비용을 회수할 시점이 없음*.
- **즉시 옵션 C (지금 서비스 분리)**: 트래픽·팀·외부 SLA 어느 것도 정당화 안 됨. outbox/이벤트 인프라 추가 비용이 댓글 도메인 단독으로 회수 불가. §6 insight-api 분리(키워드 서비스)와 묶어서 검토해야 정당.

### 관련 코드 (현재)

`comment-discussion.md` §10 코드 인벤토리 참조 — 분리 시 폴더 단위로 이동할 sub-module 가 정의돼 있다.

### 관련 문서

- `comment-discussion.md` §8(함정·운영 노트), §9(확장 포인트) — 본 결정의 *기술적* 배경.
- 본 문서 §4·§6 — BC 분리 트리거 그룹 (Solution 매칭 BC, insight-api BC).

---

## 9. Visibility (PUBLIC / PRIVATE / WORKSPACE) 도입

*기록: 2026-06-01 · 상태: **✅ 구현 완료**.*

### 배경

`ErrorCase` 에 가시성 모델이 없어 `ErrorCaseAccess.requireRead` 가 (owner OR 워크스페이스 멤버) 만 통과시켰다. 결과: **개인 케이스(workspaceId=null)는 owner 외 누구도 댓글을 달 수 없음** — 토론 채널이 의미를 잃음. 댓글 시스템(§7)과 정합하기 위해 도입.

### 채택 결정 8개

| # | 결정점 | 채택 |
|---|---|---|
| 1 | visibility 값 | **PUBLIC / PRIVATE / WORKSPACE** 3종. WORKSPACE 는 워크스페이스 멤버만 |
| 2 | 신규 케이스 기본값 | **PUBLIC** (공유 자산화 정체성 — error-archive 의 핵심 가치) |
| 3 | 익명 사용자 | PUBLIC 케이스도 **로그인은 요구**. 모든 endpoint 가 JWT 필요(기존 보안 정책 유지) |
| 4 | PUBLIC 케이스 댓글 작성 권한 | **로그인된 모든 사용자** — `requireRead` PUBLIC 분기로 자동 |
| 5 | PUBLIC 케이스 Diff 제안 상태 변경 | **여전히 owner 만** (GitHub PR author 와 동치, Visibility 무관) |
| 6 | 워크스페이스 → PUBLIC 승격 권한 | **워크스페이스 ADMIN 만** (§4 Solution 매칭과 일관 정책 — 일반 멤버가 자산 외부 유출 방지) |
| 7 | 검색·키워드 indexing | **PUBLIC 만** (§6 키워드 서비스 권한 필터와 정합 — 후속 search/insight-api 작업과 함께) |
| 8 | DB | `error_case.visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'` + `ix_error_case_visibility` index + CHECK 제약. 기존 row 마이그레이션: workspaceId 있으면 WORKSPACE, 없으면 PUBLIC |

### 구현 (요약)

| 레이어 | 변경 |
|---|---|
| 도메인 | `case/domain/model/vo/Visibility.kt` 신규 · `ErrorCase` 에 `var visibility` + `create`/`reconstitute`/`update` 시그니처 + invariant(WORKSPACE 는 workspaceId 필수) |
| 응용 | `ErrorCaseAccess.requireRead` 에 Visibility 분기 + `requireWrite` · **`requirePublicPromotion`** 신규(워크스페이스 ADMIN 검사) · `Create/UpdateErrorCaseCommand` 에 `visibility` · `GetErrorCaseUseCase.authorizeRead` 를 `ErrorCaseAccess.requireRead` 로 일원화(중복 제거) · `CreateErrorCaseUseCase` 가 WORKSPACE+workspaceId 일관성·PUBLIC 생성 시 ADMIN 검사 · `UpdateErrorCaseUseCase` 가 PUBLIC 승격 시 `requirePublicPromotion` 호출 |
| 인프라 | `ErrorCaseEntity.visibility VARCHAR(16) NOT NULL` + index · adapter toEntity/toDomain · `ErrorCaseSummary.visibility` |
| 표현 | `CreateErrorCaseRequest`(default PUBLIC) · `UpdateErrorCaseRequest`(nullable) · `ErrorCaseDetailResponse` · `ErrorCaseSummaryResponse` 노출 · controller request→command 매핑 |
| DB | `ALTER TABLE error_case ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'` + 기존 row 백필(workspaceId 있으면 WORKSPACE — 7건/25건) + CHECK + index |
| 댓글 시스템 | **변경 없음** — `requireRead` 가 단일 진입점이라 Visibility 분기가 자동 적용 |

### 채택하지 않은 안

- **PUBLIC 케이스 비로그인 read 허용**: 모든 endpoint 가 JWT 인증을 요구하는 기존 보안 정책과 충돌. 또한 익명 댓글은 모더레이션 비용이 큼. (옵션 안 채택 — 결정점 #3)
- **PUBLIC 케이스 수정도 누구나**: PUBLIC 은 *read·댓글* 만 허용. 수정은 여전히 owner 만(`requireWrite` 가 PUBLIC 케이스에서도 owner 외 거부).
- **목록(`ListErrorCasesUseCase`)에 PUBLIC 케이스 자동 노출**: 본 작업에서는 *변경 없음*. 목록 정책은 검색/인기 카드(§6 insight-api) 도입 시 함께 PUBLIC 자산 노출 — 부분적 일관성 회피.
- **개인 PUBLIC 케이스의 workspaceId 분리/이동**: 워크스페이스 이동은 본 작업 범위 밖 (`UpdateErrorCaseUseCase` 가 원래 워크스페이스 이동 불허). Visibility 만 토글 가능.

### 후속 작업

| # | 항목 | 비고 |
|---|---|---|
| 1 | 목록 정책 — PUBLIC 자산 노출 | §6 insight-api / 검색 도입과 함께. *내 케이스 + 팔로우/추천 PUBLIC* 같은 정렬 |
| 2 | Privacy Settings — 기본 visibility 토글 | §5 IA Phase 2 와 함께 |
| 3 | 운영자 강제 visibility 변경 | role 모델 도입 후 |
| 4 | visibility 변경 이력 추적 | audit log — 워크스페이스 ADMIN 행위 추적 |

### 2026-06-02 후속: `WorkspaceRole` hierarchy 명시화

직후 발견 — hierarchy(ADMIN > WRITE > READ)가 호출 측에 *부분적·간접* 표현돼있었음 (`!= null` / `canWriteContent()` / `== ADMIN` 세 가지 패턴 혼재). enum 자체가 hierarchy 모름. 새 사용처마다 일관성 부담.

**적용**: `WorkspaceRole` enum 에 `meetsOrExceeds(required)` + `canRead()` · `canWriteContent()`(기존 통일) · `canAdminister()` 메서드 추가. ordinal 기반 자연 hierarchy. 호출처 5개 모두 enum 직접 비교(`== ADMIN`) 제거하고 hierarchy 메서드로 일원화:

| 호출처 | Before | After |
|---|---|---|
| `ErrorCaseAccess.requireRead` (WORKSPACE) | `getViewerRole != null` | `?.canRead() == true` |
| `ErrorCaseAccess.requireWrite` | `role.canWriteContent()` | (그대로 — 시그니처 유지) |
| `ErrorCaseAccess.requirePublicPromotion` | `role == ADMIN` (owner/비owner 2 곳 중복) | `?.canAdminister() == true` (단일 분기로 단순화) |
| `CreateErrorCaseUseCase` (PUBLIC 생성) | `role != WorkspaceRole.ADMIN` | `!role.canAdminister()` |
| `ListErrorCasesUseCase` (멤버 검사) | `getViewerRole == null` | `?.canRead() != true` |

신규 호출처 규칙: **enum 직접 비교 금지, hierarchy 메서드 사용**. 새 role 추가 시 enum 한 곳만 갱신.

---

## 10. 비로그인(익명) 사용자 노출 범위 — Phase 단계 등재

*기록: 2026-06-02 · 상태: **Phase 1 유지(현재 = 비로그인 차단)**. Phase 2-4 정책·트리거·작업·결정점 미리 등재.*

### 배경

§9 에서 `Visibility.PUBLIC` 도입과 함께 "PUBLIC 케이스도 로그인은 요구" 로 결정(§9 결정점 #3, JWT 보안 정책 유지). 이는 *지금* 의 합리적 선택이지만, **error-archive 의 유입 채널이 검색(SEO)에 크게 의존** 하는 서비스 특성을 고려하면 *언젠가는* 비로그인 read 를 풀어야 한다. 본 §10 은 그 *전환 시점·범위·작업* 을 미리 등재해 **결정 시점에 같은 분석을 반복하지 않게** 한다.

### 서비스 특성과 유입 메커니즘

| 특성 | 시사점 |
|---|---|
| 개발자 대상 | 유입 80%+ 는 검색(예: "NPE in OrderService", "HikariPool connection timeout") |
| 공유 자산화 정체성 | PUBLIC 기본값 자체가 *"보여지길 원함"* 표명 |
| 콘텐츠가 곧 자산 | Stack Overflow / Velog 의 *콘텐츠→검색→신규 유저* 사이클이 핵심 |
| 양질 토론 필요 | 댓글·Diff 제안 작성자 신원 중요 — 익명 작성은 모더레이션 비용 폭증 |
| 페이스트 안 민감정보 | DB credentials, IP, 내부 도메인 — *노출 후 회수 불가* (Google cache, archive.org) |

**유입 funnel**: 검색결과 → PUBLIC 케이스 단건 read → 가치 발견 → 가입 → 댓글·기여. **비로그인 read 가 funnel 의 입구**.

### 업계 표준 (참고)

| 서비스 | 비로그인 read | 검색 | 액션(write/투표) |
|---|---|---|---|
| Stack Overflow | ✓ | ✓ | X — 로그인 |
| GitHub PUBLIC repo Issues | ✓ | ✓ | X |
| Reddit | ✓ | ✓ | X |
| Velog / Tistory | ✓ | ✓ | △ |

→ **공통: read 는 비로그인, 모든 write/소셜 액션은 로그인**.

### 노출 범위 7 영역

| # | 영역 | 가치 (★0-5) | risk |
|---|---|---|---|
| 1 | PUBLIC 케이스 단건 (`GET /error-cases/{id}`) | ★★★★★ — SEO 의 랜딩 단위 | 낮음 (작성자가 의도적 공개, sanitize 정책) |
| 2 | PUBLIC 케이스 단건의 댓글 read | ★★★★ — 토론이 콘텐츠 가치 증폭 | 낮음 |
| 3 | PUBLIC 케이스 단건의 Diff 제안 read | ★★★ — "이 코드 이렇게 바꿔보세요" 가 검색결과 가치 ↑ | 낮음 |
| 4 | 검색 (fingerprint / exception class / keyword) | ★★★★★ — 핵심 유입 채널 | 낮음 (결과는 PUBLIC 만) |
| 5 | PUBLIC 케이스 목록 (list) | ★★ — sitemap.xml 으로 대체 가능 | 낮음 |
| 6 | Discovery / 인기 키워드 카드 (§6 insight-api) | ★★★ — 신규 유저 충성도 형성 | 낮음 |
| 7 | 사용자 공개 프로필 (`/users/{handle}`) | ★★★ — 작성자 신원 + 다른 PUBLIC 글 발견 | 낮음 (opt-in 필요) |

**항상 로그인 필요**: 댓글 *작성* / 리액션 / 도움됨 / Diff 제안 / 케이스 작성·수정·삭제 / `/me/*` / `/workspaces/*`.

### 옵션 A/B/C/D (점진적 확장)

| 옵션 | 비로그인 허용 | 운영 비용 | SEO | 가입 funnel |
|---|---|---|---|---|
| **A. 현재(차단)** | 없음 | 0 | 0 — indexing 불가 | 검색→가입 funnel 막힘 |
| **B. 최소** | (1) 단건 + (2) 댓글 read + (3) Diff read | 낮음 | ★★★★★ | "가치 본 뒤 가입" 활성 |
| **C. 중간** | B + (4) 검색 + (5) 목록 | 중간 | ★★★★★ | 검색 페이지가 유입 채널화 |
| **D. 최대** | C + (6) Discovery + (7) 공개 프로필 | 높음 | ★★★★★+ | 비로그인이 서비스 *체험* — 충성도 형성 |

### 단계 도입 Phase 1-4

| Phase | 시점 트리거 | 채택 | 주된 작업 |
|---|---|---|---|
| **1 (현재)** | 콘텐츠 ≤100건 · UI 안정화 중 | **A 유지** | 콘텐츠·기능·UI 완성. SEO 활성화 *전* 에 핵심 가치 검증 |
| **2** | PUBLIC 케이스 100+건 + 핵심 기능 안정 + sanitize 인프라 준비 | **B** (단건 + 댓글/Diff read) | sanitize · robots.txt · sitemap.xml · meta tags · `/public/*` 라우트 + 비인증 SecurityFilterChain · IP 기반 rate limit |
| **3** | 검색 유입 월 1K+ | **C** (+ 검색·목록) | 검색결과 페이지 SSR · keyword indexing · CDN 캐싱 |
| **4** | 사용자 100+ · 인기 콘텐츠 출현 | **D** (+ Discovery + 공개 프로필) | insight-api 가동 · 공개 프로필 opt-in · CDN |

**핵심 논리**: 너무 일찍 열면 *얇은 콘텐츠* 가 indexing 되어 도메인 SEO 평판 ↓. 너무 늦게 열면 *유입 채널 자체가 닫혀* 콘텐츠 축적 안 됨. **Phase 1 → 2 트리거는 "검색 결과로 들어왔을 때 후회 안 할 만큼 콘텐츠가 알차냐" 한 가지.**

### 숨겨진 비용 (Phase 2 이상 채택 시)

| # | 항목 | 비고 |
|---|---|---|
| 1 | **민감정보 sanitize** | 페이스트 안의 DB credentials, IP, 내부 도메인 — 정규식 redaction 또는 작성 시점 경고. *노출 후엔 회수 불가* |
| 2 | **PUBLIC → PRIVATE 전환 시 회수** | Google Search Console 회수 요청 + 사이트 내부 캐시 무효화. 즉시 회수 보장 X 안내 |
| 3 | **`robots.txt` / `sitemap.xml`** | PUBLIC 케이스만 indexing 허용 / 사용자 페이지·워크스페이스 차단 |
| 4 | **meta tags** (og:title, og:description) | 검색결과/공유 미리보기 — FE SSR 또는 BE sub-resource |
| 5 | **rate limit** | 비로그인 endpoint 는 IP 기반 throttle (RestClient interceptor + Redis) |
| 6 | **SecurityFilterChain 분기** | 현재 모든 endpoint 가 JWT 필요 — `/api/v1/public/**` 별도 비인증 경로 |
| 7 | **GDPR / 개인정보 표시** | PUBLIC 작성 시 *"작성자 이름·아바타가 비로그인에게도 보임"* 명시. 공개 프로필 별도 opt-in |
| 8 | **콘텐츠 품질 게이트** | indexing 전 minimum 품질 (제목 길이, 본문 길이, status≠DRAFT) — 얇은 콘텐츠가 도메인 SEO 평판 ↓ |

### 미리 정해둘 결정점 5개

| # | 결정점 | 후보 / 권고 |
|---|---|---|
| 1 | **Phase 2 트리거 임계** | PUBLIC 케이스 100건 + 정성 평가("후회 안 할 만큼 알찬가") 둘 다 |
| 2 | **민감정보 sanitize 시점** | **작성 시점** (FE 정규식 redaction + BE 검증) — 노출 후 sanitize 는 의미 X. 정규식 set: AWS access key·DB connection string·email·IPv4·private domain |
| 3 | **PUBLIC → PRIVATE 회수 정책** | "즉시 비공개 + 검색엔진 cache 는 며칠 걸림" 안내 + 옵션으로 Search Console URL 회수 자동 요청 |
| 4 | **비인증 endpoint 라우팅** | **별도 경로** (`/api/v1/public/error-cases/{id}`) — SecurityFilterChain 분기 단순화. 권한 분기 코드 안 늘어남. 같은 경로 + 권한 분기는 향후 익명 read 의 응답 형태가 인증 read 와 *덜* 같을 수 있어 ACL 부담 ↑ |
| 5 | **공개 프로필 opt-in** | **기본 OFF**. 작성자가 명시적으로 *"내 PUBLIC 글 모아 보이게"* 켜야. iam-api 의 user setting 으로 보관 |

### 채택하지 않은 안

- **Phase 2 지금 도입**: 콘텐츠 25건 + sanitize 인프라 부재. SEO 평판 손상 위험.
- **PUBLIC 케이스도 영구 차단 (현재 유지)**: 서비스 특성과 충돌 — 정체성 자체가 *공유 자산*. 콘텐츠 축적 funnel 없음.
- **비로그인 댓글 작성 허용** (Velog 일부 옵션): 모더레이션 비용 폭증, 양질 토론 어려움, 신원 추적 불가.
- **같은 경로 + 권한 분기** (결정점 #4 대안): 익명 read 의 응답 stripping(작성자 email/PII 제외)·rate limit·캐싱 정책이 인증 read 와 다른데 한 endpoint 안에서 조건 분기하면 *그 endpoint 가 두 책임*. 별도 경로가 깨끗.

### 후속 작업 (Phase 2 도입 시점)

1. **인프라 작업** (위 "숨겨진 비용" 8개 모두)
2. **Visibility(§9) 와의 정합 점검**: `requireRead` 가 PUBLIC 분기에서 *비인증 사용자* 도 통과시켜야 함 — 현재는 `requesterUserId: Long` 시그니처. nullable userId 또는 별도 entry point 필요 (결정점 #4 의 *별도 경로* 권고가 이 문제도 해결).
3. **`comment-discussion.md` §6 권한 매트릭스** 갱신 — 비로그인 read 분기 추가
4. **`errorcase-domain.md` §6 권한 모델** 의 `requireRead` 시그니처 갱신

