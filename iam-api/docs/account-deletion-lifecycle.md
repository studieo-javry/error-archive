# 회원 탈퇴(Account Deletion) 라이프사이클과 정책

> 최종 갱신: 2026-05-28
> 관련 변경: 루트 `docs/changelog.md` 의 "회원 탈퇴 — soft delete + 30일 grace + 자동 ADMIN 승격" 항목.
> 이 문서는 회원 탈퇴가 **어떤 상태를 거치는지**, **각 단계에서 어떤 데이터가 어떻게 처리되는지**, **왜 이렇게 설계했는지(대안 포함)**, **운영에서 무엇을 더 챙겨야 하는지**를 한 곳에 정리한다.

---

## 0. TL;DR — 한눈에 보기

회원 탈퇴는 **3-상태 soft delete**. 사용자가 마음을 바꿀 30일 grace 를 두고, 만료되면 배치가 PII 를 익명화·OAuth identity 를 삭제해 **확정**한다. 콘텐츠(core-api 의 에러케이스/스니펫/첨부)는 그대로 두고 프런트가 status 를 보고 "탈퇴한 사용자"로 표시(ghost).

```
   사용자          요청 시(즉시)              grace 기간(기본 30일)        만료 후 배치
     │                │                            │                          │
     ▼                ▼                            ▼                          ▼
   [ACTIVE]  ──DELETE /me──▶  [PENDING_DELETION]  ──시간 경과──▶          [DELETED]
                              │  email/이름/avatar/bio 그대로           email/avatar/bio = null
                              │  로그인 가능(복구 결정용)                displayName = deleted_user_{id}
                              │                                          OAuth identity 삭제
                              │
                              └──POST /me/restore──▶  [ACTIVE]
                                  (grace 기간 안에서만)
```

핵심 정책:
- **Soft delete + PII 익명화** — 도메인이 이미 `UserStatus.DELETED` enum 을 가지고 있어 자연스러움. 외부 컨슈머의 user_id 참조가 깨지지 않게 row 자체는 보존.
- **30일 grace period** — 사용자 실수/마음 변경 회복 가능.
- **자동 ADMIN 승격** — 사용자가 마지막 ADMIN 인 워크스페이스는 다음 멤버를 ADMIN 으로 자동 승격해 관리자 공백 방지.
- **Core-api ghost data** — iam-api 가 core-api 로 cross-service 호출을 하지 않는다. core-api 의 `owner_user_id`/`uploaded_by_user_id` 는 그대로 남고, 프런트가 status=DELETED 인 사용자를 보면 "탈퇴한 사용자"로 렌더.

---

## 1. 상태 모델 — `iam_user`

| 상태 | `status` | `pending_deletion_at` | `email`/`displayName`/`avatarUrl`/`bio` | `canSignIn()` | OAuth identity |
|------|---------|----------------------|------------------------------------------|---------------|---------------|
| **ACTIVE** | `ACTIVE` | `NULL` | 원본 PII | `true` | 보유 |
| **PENDING_DELETION** | `PENDING_DELETION` | grace 시작 시각 | **원본 그대로**(복구 가능성) | `true` (복구 결정 위해) | 보유 |
| **SUSPENDED** | `SUSPENDED` | `NULL` | 원본 | `false` | 보유 |
| **DELETED** (확정) | `DELETED` | `NULL` | `null` / `deleted_user_{id}` / `null` / `null` | `false` | **삭제됨** |

> `PENDING_DELETION` 도 `canSignIn() == true` 인 이유: 사용자가 같은 OAuth 로 다시 로그인해서 "복구하기"를 누를 기회를 줘야 한다. 일반 API 권한은 그대로 살아 있어 그 자체로 활동도 가능 — 의도된 단순화(복구를 막힘 없이).

상태 전이는 도메인 객체가 책임진다:
- `User.requestDeletion(at)` — ACTIVE → PENDING_DELETION (다른 상태에서 호출 시 `check` 실패).
- `User.restore(at)` — PENDING_DELETION → ACTIVE.
- `User.finalizeDeletion(at)` — PENDING_DELETION → DELETED + **PII 익명화**(메서드 안에서). 이미 DELETED 면 멱등 no-op.

---

## 2. 단계별 동작

### 2.1 요청 — `DELETE /api/v1/users/me`

본인만(`Authentication.sub`). 단일 트랜잭션 안에서:

1. **워크스페이스 멤버십 정리**(`TransferMembershipsOnUserLeaveUseCase.handleLeaveAll`):
   - 각 멤버십 별로 자동 ADMIN 승격 평가(§4) → 본인 멤버 row 삭제.
2. **팔로우 양방향 엣지 삭제** — `FollowRepositoryPort.deleteAllByUser(userId)` (followerId OR followeeId).
3. **refresh 토큰 전부 폐기** — `RefreshTokenRepositoryPort.revokeAllByUserId(userId)` (`revoked_at` 세팅). 모든 세션 즉시 만료.
4. **`User.requestDeletion()` + `userRepository.save(user)`** — status=PENDING_DELETION, pending_deletion_at=now.
5. 응답 헤더 `Set-Cookie` 로 `iam_refresh` 쿠키 만료(`Max-Age=0`).

응답 `204 No Content`. 본문 없음. SPA 가 직후에 `GET /users/me` 하면 `pendingDeletionAt` 으로 만료일 계산해 안내 가능.

> ⚠️ 1~5의 순서가 중요하다 — §6 함정 기록 참조.

#### 실패 응답
| 상태 | HTTP | 본문(`detail`) |
|------|------|----------------|
| 사용자 없음 | 404 | `user not found: {id}` |
| 이미 `PENDING_DELETION` | 204 | (멱등 no-op, `alreadyPending=true` 내부 표시) |
| `SUSPENDED`/`DELETED` 등에서 호출 | 409 | `only ACTIVE users can request deletion: current=...` |

### 2.2 복구 — `POST /api/v1/users/me/restore`

본인만. grace 기간 안에서만 의미 있음.

1. `User.restore()` — PENDING_DELETION → ACTIVE, `pending_deletion_at = null`.
2. `userRepository.save(user)`.

응답 `204`. 

> **복원하지 않는 것**: 워크스페이스 멤버십·팔로우 엣지는 탈퇴 시점에 이미 삭제됐고 **복원하지 않는다**. 의도된 단순화 — 멤버십을 보관·복원 처리하면 *마지막 ADMIN 자동 승격*과 충돌(돌아온 사용자가 다시 ADMIN 인가? 승격된 다른 사람은 강등?)해 복구 로직이 폭발한다. 사용자가 다시 가입/팔로우 절차를 거치게 한다.

#### 실패 응답
| 상태 | HTTP |
|------|------|
| 이미 `ACTIVE` | 204 (멱등) |
| `DELETED`/`SUSPENDED` 등 | 409 |

### 2.3 확정 — 배치 `FinalizeDeletedAccountsUseCase`

`AccountDeletionScheduler.@Scheduled(fixedDelayString = "PT1H")` 가 매 시간 호출. 부팅 후 5분 뒤 첫 실행.

처리:
1. `userRepository.findPendingDeletionBefore(now - gracePeriod, batchSize)` — 그 페이지가 비면 종료.
2. 각 사용자에 대해 **`TransactionTemplate` 으로 독립 트랜잭션** 안에서:
   - `User.finalizeDeletion()` — status=DELETED + PII 익명화(`email=null`, `displayName="deleted_user_{id}"`, `avatarUrl=null`, `bio=null`).
   - `userRepository.save(user)`.
   - `socialIdentities.deleteByUserId(userId)` — 같은 OAuth 계정으로 추후 재가입 가능하게 해제.
3. 다음 배치 반복(빈 페이지 또는 부분 페이지면 종료).

각 호출이 독립 트랜잭션이라 **한 건 실패가 다른 건을 롤백시키지 않는다**. 도메인의 `finalizeDeletion()` 이 이미 `DELETED` 면 no-op 이므로 **멱등**.

> `TransactionTemplate` 을 쓴 이유: 같은 빈 내부에서 `@Transactional` 메서드를 호출하면 Spring AOP 프록시를 거치지 않아 트랜잭션이 시작되지 않는 self-invocation 함정. 명시 트랜잭션으로 회피.

---

## 3. 정책 결정 — 채택안과 대안

각 결정에 대해 **다른 선택지가 있었고**, **운영 상황이 바뀌면 다시 고를 수 있다**(이 문서는 `revisitable-decisions` 의 정신을 따른다).

### 3.1 Soft delete vs Hard delete
- **채택: soft delete + PII 익명화** — 도메인이 이미 `UserStatus.DELETED` 를 가지고 있고, 외부 user_id 참조(core-api 의 owner/uploader, follow 기록 등)가 깨지지 않게 row 자체는 남긴다.
- 대안 (hard delete): user row 삭제 + 모든 cross-service FK cascade. cross-service 동기 호출이 필요하고 부분 실패 시 일관성 깨짐.

### 3.2 마지막 ADMIN 처리
- **채택: 자동 강등/위임** — WRITE 멤버 중 가장 오래된 사람을 ADMIN 으로 자동 승격. 사용자에게 마찰 없음.
- 대안 (탈퇴 차단): 다른 ADMIN 임명 후 다시 시도하라고 409. 안전하지만 사용자가 "이미 떠나기로 결정했는데 왜 추가 액션?" 마찰. 사용자 결정.
- 대안 (워크스페이스도 같이 soft-delete): 멤버 입장에선 갑자기 사라지는 경험.
- **부작용 수용**: 의도치 않게 관리자가 되는 사람이 생긴다. 워크스페이스 알림으로 보완 권장(미구현).

### 3.3 Core-api 잔여 데이터 (owner_user_id / uploaded_by_user_id)
- **채택: 그대로 두기 (ghost)** — iam-api 가 core-api 로 동기 호출하지 않는다. 가장 작은 변경, cross-service 일관성 문제 없음. 프런트가 `users/{id}.status=DELETED` 면 "탈퇴한 사용자" 로 렌더.
- 대안 (cross-service UPDATE): user_id=0 등 tombstone 으로 일괄 익명화. 분산 트랜잭션 없어 부분 실패 위험.
- 대안 (이벤트 발행: `UserDeleted`): 정석. 메시지 브로커(Kafka 등) 인프라 추가 필요. 현재 프로젝트엔 없음. **확장 포인트** — §8 참조.
- 대안 (cascade 삭제): 사용자가 등록한 콘텐츠를 워크스페이스 동료가 보고 있다면 갑자기 사라짐. 데이터 소실.

### 3.4 Grace period
- **채택: 30일 grace + `PENDING_DELETION` 상태** — 실수 복구 가능, 업계 표준(GitHub 30일 등).
- 대안 (즉시 DELETED): 단순. 실수 복구 불가.
- 만료 처리는 `AccountDeletionScheduler` (§2.3). `iam.account-deletion.grace-period=PT720H` (Duration) 으로 조정 가능.

### 3.5 OAuth identity 처리
- **채택: finalize 시 삭제** — `DELETED` 로 확정되는 순간 같은 GitHub 계정으로 깨끗하게 재가입 가능. grace 기간엔 유지(같은 OAuth 로 재로그인 → 복구).
- 대안 (영구 보존 + 차단): 같은 OAuth 로 재가입 불가. 정책상 강하지만 사용자가 다시 들어오기를 막아 친화도 낮음.

---

## 4. 자동 ADMIN 승격 규칙 (`TransferMembershipsOnUserLeaveUseCase`)

떠나는 사용자가 ADMIN 이고 그 워크스페이스의 **유일한 ADMIN** 일 때:

```
정렬 키: (role.rank desc, joined_at asc)
       └─ ADMIN > WRITE > READ      └─ 가장 오래된 사람부터
```

1. **WRITE 멤버가 있으면** 가장 오래된 WRITE → ADMIN.
2. **WRITE 없이 READ 만 있으면** 가장 오래된 READ → ADMIN.
3. **본인이 유일 멤버**면 워크스페이스도 hard-delete(`workspaceRepository.delete(workspaceId)`). 콘텐츠는 core-api 가 자체적으로 보존.

다른 ADMIN 이 이미 있으면 승격 없이 본인 멤버 row 만 삭제.

---

## 5. Cross-cutting 영향 매트릭스

| 데이터 | DELETE 즉시 | grace 중 | finalize 시 |
|--------|------------|----------|-------------|
| `iam_user.status` | `PENDING_DELETION` | (유지) | `DELETED` |
| `iam_user.pending_deletion_at` | now() | (유지) | `null` |
| `iam_user.email` / `display_name` / `avatar_url` / `bio` | (원본 유지) | (유지) | **익명화** (`null` / `deleted_user_{id}` / `null` / `null`) |
| `iam_refresh_token` | 본인 토큰 전부 폐기(`revoked_at`) | — | — |
| `iam_refresh` 쿠키 | `Set-Cookie Max-Age=0` 으로 만료 | — | — |
| `iam_workspace_member` | 본인 멤버십 전부 삭제 (사전 ADMIN 자동 승격) | — | — |
| `iam_workspace` | 본인이 유일 멤버인 워크스페이스 hard-delete | — | — |
| `iam_workspace_invitation` | (변경 없음 — `invited_by_user_id`/`accepted_by_user_id` 감사용 유지) | — | — |
| `social_follow` | follower/followee 양방향 엣지 전부 삭제 | — | — |
| `iam_social_identity` | (유지 — 복구를 위해 grace 동안 살림) | (유지) | **삭제** |
| core-api `error_case.owner_user_id` 등 | (변경 없음 — ghost) | — | — |

---

## 6. 함정 기록 — bulk `@Modifying` 의 컨텍스트 clear

초기 구현에서 `user.requestDeletion()` + `userRepository.save(user)` 를 정리 작업 **앞**에 두니, DELETE 가 204 를 반환하고 로그에 "moved to PENDING_DELETION" 까지 찍혔는데 **DB 의 user.status 는 여전히 ACTIVE**.

원인:
- `WorkspaceMember`/`Follow`/`RefreshToken` 의 bulk 쿼리는 `@Modifying(clearAutomatically = true)` (Spring Data 기본 `flushAutomatically = false`).
- 호출 순서:
  1. `save(user)` → managed entity 의 status 가 PENDING_DELETION 으로 변경됨(아직 flush 안 됨).
  2. `memberRepo.delete(...)` 등 bulk @Modifying — `clearAutomatically=true` 라 실행 후 persistence context 를 **clear**.
  3. **앞서 schedule 된 user UPDATE 가 flush 되지 않은 채 entity 가 detach** → UPDATE 영영 안 발행.
  4. 트랜잭션 commit. user.status 그대로.

해결:
- `RequestAccountDeletionUseCase` 안에서 **정리 작업 먼저, `userRepository.save(user)` 는 가장 마지막**.
- 일반화하면: bulk @Modifying 을 호출하기 전에 entity 변경을 save 했다면 명시적으로 `flush` 하거나, 순서를 바꿔 마지막에 save.

대안(채택 안 함):
- 모든 @Modifying 에 `flushAutomatically = true` 추가 — 광범위한 변경. 다른 유스케이스에 부수 효과.
- `UserRepositoryAdapter.save` 를 `saveAndFlush` 로 변경 — 모든 사용자 변경마다 즉시 flush. 과함.

---

## 7. 운영 절차

### 7.1 Flyway 마이그레이션 (운영)
로컬은 `ddl-auto: update` 로 컬럼이 자동 추가됐지만, `update` 는 CHECK constraint 를 수정하지 않으므로 운영에선 Flyway 로 명시:

```sql
-- V{N}__add_pending_deletion.sql
ALTER TABLE iam_user
    ADD COLUMN pending_deletion_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE iam_user
    DROP CONSTRAINT iam_user_status_check;

ALTER TABLE iam_user
    ADD CONSTRAINT iam_user_status_check
    CHECK (status IN ('ACTIVE', 'PENDING_DELETION', 'SUSPENDED', 'DELETED'));
```

### 7.2 ShedLock (다중 인스턴스)
현재 `AccountDeletionScheduler` 는 **단일 인스턴스 가정**. 운영에서 N 인스턴스를 띄우면 모든 인스턴스가 동시에 같은 배치를 돌릴 수 있다(중복 작업·로그 폭증). core-api 의 orphan GC 가 이미 `net.javacrumbs.shedlock` + `@SchedulerLock` 으로 잡고 있으니 같은 패턴 적용 권장:

```kotlin
@SchedulerLock(name = "account-deletion-finalize", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5M")
@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
fun runFinalize() { ... }
```

dependency·`shedlock` 테이블·`@EnableSchedulerLock` 추가 필요. 미적용 시 단일 인스턴스 운영하거나 배치를 별도 cron job 으로 분리.

### 7.3 설정
| 키 | 기본값 | 설명 |
|----|-------|------|
| `iam.account-deletion.grace-period` | `PT720H` (30일) | PENDING_DELETION → DELETED 까지의 grace 기간 |
| `iam.account-deletion.batch-size` | `200` | finalize 배치 1회 처리 건수 |
| 스케줄 간격 | `PT1H` | `AccountDeletionScheduler.@Scheduled.fixedDelayString` (코드 상수, 필요 시 외부화) |

### 7.4 모니터링 포인트
- `AccountDeletionScheduler.runFinalize` 실패 로그 (`[account-finalize-scheduler] batch failed`).
- 개별 finalize 실패 (`[account-finalize] failed userId=...`).
- 워크스페이스 자동 ADMIN 승격 (`[leave] workspace=X auto-promoted user=...`).
- 유일 멤버 워크스페이스 삭제 (`[leave] workspace=X deleted (sole member ... left)`).

---

## 8. 확장 포인트

### 8.1 `UserDeleted` 이벤트 발행 (core-api 와의 진짜 분리)
현재는 ghost data 정책으로 cross-service 호출 자체를 안 한다. 콘텐츠 작성자 표시·뱃지·집계 등 core-api 가 **사용자가 사라졌음을 알아야** 하는 시점이 오면:
- 발신: `FinalizeDeletedAccountsUseCase.finalizeOne` 안에서 outbox 패턴(`iam_outbox` 테이블에 row 적재 + 트랜잭션 commit 후 publisher 가 송신).
- 수신: core-api 가 컨슈머. 예: 캐시된 displayName 무효화, 표시명 "deleted_user_{id}" 로 갱신.

메시지 브로커(Kafka/RabbitMQ/SQS) 도입 시 함께.

### 8.2 명시적 휴지통 UI
현재는 grace 가 *암묵적*. 명시적으로 노출하려면:
- `GET /users/me/deletion-status` — `pendingDeletionAt`, `deletionScheduledAt`(만료일), `cancellable=true` 등.
- 만료 N일 전 이메일 알림.

### 8.3 데이터 export (GDPR 대응)
탈퇴 전 사용자 자기 데이터 다운로드(에러케이스/첨부 메타/스니펫 등). cross-service 집계 필요 — iam-api 가 core-api 호출하거나 별도 export 서비스.

### 8.4 SUSPENDED 와의 상호작용
현재 SUSPENDED 사용자는 DELETE 호출 시 409. 정책: 운영팀이 먼저 사용자를 unsuspend 한 뒤 사용자가 직접 탈퇴 요청, 또는 운영팀이 명시적 강제 삭제 API 사용.

---

## 9. 관련 코드

도메인:
- `auth/domain/model/User.kt` — `requestDeletion`/`restore`/`finalizeDeletion`/`anonymizedDisplayName` 컴패니언.
- `auth/domain/model/vo/UserStatus.kt` — enum + `canSignIn()` 정책.

응용:
- `auth/application/usecase/RequestAccountDeletionUseCase.kt` — 단일 트랜잭션 진입점.
- `auth/application/usecase/RestoreAccountUseCase.kt` — 복구.
- `auth/application/usecase/FinalizeDeletedAccountsUseCase.kt` — 배치(`TransactionTemplate` per-user).
- `workspace/application/usecase/TransferMembershipsOnUserLeaveUseCase.kt` — 자동 ADMIN 승격.
- 포트: `UserRepositoryPort.findPendingDeletionBefore`, `SocialIdentityRepositoryPort.deleteByUserId`, `FollowRepositoryPort.deleteAllByUser`.

인프라:
- `auth/infrastructure/jpa/entity/UserEntity.kt` — `pending_deletion_at` 컬럼.
- `auth/infrastructure/jpa/UserJpaRepository.kt` — `findByStatusAndPendingDeletionAtBeforeOrderByPendingDeletionAtAsc`.
- `auth/infrastructure/jpa/SocialIdentityJpaRepository.kt` — `@Modifying deleteByUserId`.
- `social/infrastructure/jpa/FollowJpaRepository.kt` — `@Modifying deleteAllByUser`.
- `auth/infrastructure/schedule/AccountDeletionScheduler.kt` — `@Scheduled` 진입점.

설정:
- `auth/config/AccountDeletionProperties.kt` — grace/batchSize.
- `auth/config/AccountDeletionConfig.kt` — `@EnableScheduling` + `TransactionTemplate` 빈.
- `auth/config/AuthConfigRegistration.kt` — `@EnableConfigurationProperties` 에 등록.

표현:
- `auth/presentation/web/UserController.kt` — `DELETE /me`, `POST /me/restore` (Swagger 어노테이션 포함).
- `auth/presentation/web/dto/response/MyProfileResponse.kt` — `pendingDeletionAt` 필드.

참고:
- 루트 `docs/changelog.md` — "회원 탈퇴" 항목.
- `core-api/docs/revisitable-decisions.md` — 결정 재검토 패턴.
- `core-api/docs/attachment-lifecycle.md` — 같은 스타일의 라이프사이클 문서(첨부).
