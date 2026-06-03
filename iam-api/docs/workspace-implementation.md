# iam-api Workspace BC Implementation Reference

워크스페이스 도메인의 구현 레퍼런스. error-case/timeline 등 다른 BC의 콘텐츠는 모르며, userId 참조만으로 동작한다.

---

## 0. 디렉터리 한눈에 보기

```
src/main/kotlin/org/studieojavry/iamapi/workspace/
├── domain/
│   └── model/
│       ├── Workspace.kt
│       ├── WorkspaceMember.kt
│       ├── WorkspaceInvitation.kt
│       └── vo/
│           ├── WorkspaceRole.kt
│           ├── WorkspaceName.kt
│           ├── WorkspaceSettings.kt
│           ├── InvitationType.kt
│           └── InvitationStatus.kt
├── application/
│   ├── command/
│   │   ├── CreateWorkspaceCommand.kt
│   │   ├── UpdateWorkspaceCommand.kt
│   │   ├── ChangeMemberRoleCommand.kt
│   │   ├── InviteMemberCommand.kt
│   │   └── AcceptInvitationCommand.kt
│   ├── port/
│   │   ├── WorkspaceRepositoryPort.kt
│   │   ├── WorkspaceMemberRepositoryPort.kt
│   │   ├── WorkspaceInvitationRepositoryPort.kt
│   │   ├── MemberSummaryReaderPort.kt
│   │   ├── InvitationTokenGeneratorPort.kt
│   │   └── InvitationEmailSenderPort.kt
│   └── usecase/
│       ├── WorkspaceAccess.kt          # 권한 가드 유틸
│       ├── CreateWorkspaceUseCase.kt
│       ├── UpdateWorkspaceUseCase.kt
│       ├── DeleteWorkspaceUseCase.kt
│       ├── GetWorkspaceUseCase.kt
│       ├── ListMyWorkspacesUseCase.kt
│       ├── ListWorkspaceMembersUseCase.kt
│       ├── ChangeMemberRoleUseCase.kt
│       ├── RemoveMemberUseCase.kt
│       ├── InviteMemberUseCase.kt
│       ├── ListInvitationsUseCase.kt
│       ├── RevokeInvitationUseCase.kt
│       ├── AcceptInvitationUseCase.kt
│       └── PreviewInvitationUseCase.kt
├── infrastructure/
│   ├── jpa/
│   │   ├── WorkspaceEntity.kt + JpaRepository + Adapter
│   │   ├── WorkspaceMemberEntity.kt + JpaRepository + Adapter
│   │   └── WorkspaceInvitationEntity.kt + JpaRepository + Adapter
│   ├── token/InvitationTokenGeneratorAdapter.kt
│   ├── email/LoggingInvitationEmailSenderAdapter.kt
│   └── membersummary/MemberSummaryReaderAdapter.kt
├── presentation/web/
│   ├── WorkspaceController.kt
│   ├── WorkspaceMemberController.kt
│   ├── WorkspaceInvitationController.kt
│   ├── WorkspaceExceptionHandler.kt
│   └── dto/
│       ├── request/{Create,Update}WorkspaceRequest.kt, ChangeMemberRoleRequest.kt,
│       │           CreateInvitationRequest.kt, AcceptInvitationRequest.kt
│       └── response/Workspace*.kt, WorkspaceMemberResponse.kt, InvitationResponse.kt
└── config/WorkspaceProperties.kt (+ Registration)
```

---

## 1. 바운디드 컨텍스트 경계

| 알고 있다 | 모른다 |
|---|---|
| Workspace, WorkspaceMember, WorkspaceInvitation, WorkspaceRole, WorkspaceSettings | error-case, timeline, attachment 등 콘텐츠 |
| `userId: Long` 참조 | `auth.User` 도메인 객체(직접 접근 금지) |
| `MemberSummaryReaderPort`(자체 정의) | `auth.UserRepositoryPort` |

`MemberSummaryReaderPort`로만 auth와 통신. 어댑터는 `auth.infrastructure.jpa.UserJpaRepository`를 내부에서 사용하지만, 도메인/유스케이스 코드는 auth의 어떤 도메인 클래스도 import하지 않는다.

---

## 2. 도메인 (`domain/model`)

### `vo/WorkspaceRole`
- `READ`(rank=1), `WRITE`(rank=2), `ADMIN`(rank=3)
- `canManageMembers()`, `canEditWorkspace()`, `canWriteContent()`, `canRead()` — 권한 의미는 다른 BC가 사용

### `vo/InvitationType`
- `EMAIL` — 이메일 1개로 특정 수신자에게 발송
- `LINK` — 토큰 자체를 공유(누구나 클릭 가능)

### `vo/InvitationStatus`
- `PENDING` → `ACCEPTED` / `REVOKED` / `EXPIRED`. terminal 상태에서 추가 변경 불가.

### `vo/WorkspaceName`
- 1~50자 검증된 value class.

### `vo/WorkspaceSettings`
- `notificationEnabled`(기본 true), `defaultTimezone`(기본 "UTC"). 다른 BC 콘텐츠 정책은 들어오지 않는다.

### `Workspace`
- private 생성자 + `create(name, createdByUserId)` / `rehydrate(...)`.
- `rename(WorkspaceName)`, `updateSettings(WorkspaceSettings)` — 동일성 체크 후 `updatedAt` 갱신.

### `WorkspaceMember`
- private 생성자 + `join(workspaceId, userId, role)` / `rehydrate(...)`.
- `changeRole(WorkspaceRole)` — 같은 값이면 no-op.
- 한 워크스페이스 안에 같은 userId 중복 불가(테이블 UNIQUE 제약).

### `WorkspaceInvitation`
- 필드: `workspaceId, invitedByUserId, type, email?, tokenHash, role, status, expiresAt, createdAt, acceptedAt?, acceptedByUserId?`
- `init` 가드:
  - 토큰 해시 비어있지 않음
  - **role != ADMIN** — 초대 단계에서 ADMIN 부여 차단(기존 멤버를 promote만 가능)
  - EMAIL이면 `email` 필수 / LINK면 `email == null`
- 동작: `isPending()`, `markExpiredIfNeeded()`(만료된 PENDING → EXPIRED), `accept(byUserId)`(상태 + 시각 + 수락자 기록), `revoke()`(terminal이면 no-op).

---

## 3. Application

### 3.1 Ports

| 포트 | 책임 | 어댑터 |
|---|---|---|
| `WorkspaceRepositoryPort` | Workspace CRUD | `WorkspaceRepositoryAdapter` |
| `WorkspaceMemberRepositoryPort` | 멤버 CRUD/조회/role count/일괄 삭제 | `WorkspaceMemberRepositoryAdapter` |
| `WorkspaceInvitationRepositoryPort` | 초대 저장/조회(by id, by tokenHash, pending list)/일괄 삭제 | `WorkspaceInvitationRepositoryAdapter` |
| `MemberSummaryReaderPort` | auth.User → 멤버 표시용 요약 | `MemberSummaryReaderAdapter` (auth.UserJpaRepository 사용) |
| `InvitationTokenGeneratorPort` | 256-bit URL-safe Base64 | `InvitationTokenGeneratorAdapter` |
| `InvitationEmailSenderPort` | 초대 이메일 발송 | `LoggingInvitationEmailSenderAdapter` (TODO: SMTP/SES 연동) |

### 3.2 권한 가드 — `WorkspaceAccess`
- `requireMember(repo, workspaceId, userId)` → 비멤버면 `AccessDeniedException`
- `requireAdmin(repo, workspaceId, userId)` → 멤버여야 하고 role==ADMIN

도메인 메서드가 아닌 이유: 멤버 목록이 별도 애그리거트라서 repository 조회가 필요. 컨트롤러가 아닌 유스케이스 진입부에서 호출해야 정책 누락 방지.

### 3.3 Use Cases

| 유스케이스 | 트랜잭션 | 핵심 로직 |
|---|---|---|
| `CreateWorkspaceUseCase` | RW | `User` ACTIVE 검증 → Workspace 저장 → 작성자를 ADMIN 멤버로 즉시 등록 |
| `UpdateWorkspaceUseCase` | RW | ADMIN 가드 → name/settings patch (null=미변경) |
| `DeleteWorkspaceUseCase` | RW | ADMIN 가드 → invitations/members/workspace 순서로 cascade 삭제 |
| `GetWorkspaceUseCase` | RO | requireMember → 워크스페이스 + viewerRole 반환 |
| `ListMyWorkspacesUseCase` | RO | userId 멤버십 → workspaceId desc 정렬 |
| `ListWorkspaceMembersUseCase` | RO | requireMember → ADMIN 먼저, 가입일 오름차순. summary 일괄 조회로 N+1 방지 |
| `ChangeMemberRoleUseCase` | RW | ADMIN 가드 → 마지막 ADMIN 강등 차단 |
| `RemoveMemberUseCase` | RW | self leave 허용 / 그 외 ADMIN 가드. 마지막 ADMIN 제거 차단 |
| `InviteMemberUseCase` | RW | ADMIN 가드 → ADMIN 초대 거부 → TTL 클램프 → 토큰 생성/해시 → EMAIL이면 발송, LINK면 응답에 토큰/URL 포함 |
| `ListInvitationsUseCase` | RO | ADMIN 가드 → PENDING 목록 |
| `RevokeInvitationUseCase` | RW | ADMIN 가드 → status REVOKED |
| `AcceptInvitationUseCase` | RW | 토큰 해시 조회 → expired 자동 표시 → EMAIL 타입은 수락자 이메일 일치 검사 → 멤버로 추가(이미 멤버면 그대로) → status ACCEPTED |
| `PreviewInvitationUseCase` | RO | 비로그인용. 워크스페이스 이름/role/만료/`usable` 반환 |

### 3.4 보안 / 정책 요약
- **토큰**: 256-bit URL-safe Base64, DB엔 SHA-256 해시만 저장(평문 미보존).
- **TTL**: 기본 7일, 최대 30일. 요청 값은 `coerceIn`으로 강제.
- **EMAIL 초대 타깃**: 이미 ACTIVE 멤버면 거부.
- **수락 시 이메일 매치**: EMAIL 초대는 수락자 계정 이메일과 일치해야 통과 — 토큰만 알면 누구나 합류하는 것을 막기 위함. LINK 초대는 의도적으로 누구나 가능.
- **마지막 ADMIN 보호**: 강등/추방/스스로 떠나기 모두 차단.
- **초대로 ADMIN 부여 금지**: 도메인 init + 유스케이스에서 이중 차단.

---

## 4. 인프라 (`infrastructure/`)

### JPA 테이블

| 테이블 | 핵심 제약 |
|---|---|
| `iam_workspace` | `created_by_user_id` 인덱스. settings는 `@Embeddable`(notification_enabled, default_timezone). |
| `iam_workspace_member` | `(workspace_id, user_id)` UNIQUE. `user_id`/`workspace_id` 인덱스. `@Modifying` JPQL로 cascade 삭제. |
| `iam_workspace_invitation` | `token_hash` UNIQUE. `workspace_id`/`status` 인덱스. |

### 토큰/이메일/요약
- `InvitationTokenGeneratorAdapter` — `SecureRandom` + Base64 URL-safe(no padding).
- `LoggingInvitationEmailSenderAdapter` — 실제 SMTP 미연동. 로그로 확인 후 운영 도입 시 어댑터 교체.
- `MemberSummaryReaderAdapter` — `UserJpaRepository.findAllById` 일괄 조회 + 새로 추가된 `findFirstByEmailIgnoreCase` 사용.

---

## 5. Config

| 클래스 | prefix | 키 |
|---|---|---|
| `WorkspaceProperties` | `iam.workspace` | `inviteBaseUrl`(필수), `defaultInvitationTtlHours`(168), `maxInvitationTtlHours`(720), `maxMembersPerWorkspace`(200) |

`inviteBaseUrl`: 백엔드가 `${base}/{token}` 형태로 acceptUrl을 만든다. 운영 시 FE 배포 도메인으로 덮어쓰기.

---

## 6. 엔드포인트

### Workspace
| Method | Path | 인증 | 비고 |
|---|---|---|---|
| POST   | `/api/v1/workspaces` | JWT | 생성자 → 즉시 ADMIN |
| GET    | `/api/v1/workspaces` | JWT | 내가 멤버인 워크스페이스 목록 |
| GET    | `/api/v1/workspaces/{id}` | JWT(멤버) | 본인 role 포함 |
| PATCH  | `/api/v1/workspaces/{id}` | JWT(ADMIN) | name/notificationEnabled/defaultTimezone patch |
| DELETE | `/api/v1/workspaces/{id}` | JWT(ADMIN) | invitations + members + workspace cascade |

### Members
| Method | Path | 인증 | 비고 |
|---|---|---|---|
| GET    | `/api/v1/workspaces/{id}/members` | JWT(멤버) | role 정렬, profileHref 포함 |
| PATCH  | `/api/v1/workspaces/{id}/members/{userId}` | JWT(ADMIN) | body `{role}` |
| DELETE | `/api/v1/workspaces/{id}/members/{userId}` | JWT(ADMIN) | 추방 |
| DELETE | `/api/v1/workspaces/{id}/members/me` | JWT | 스스로 나가기 |

### Invitations
| Method | Path | 인증 | 비고 |
|---|---|---|---|
| POST   | `/api/v1/workspaces/{id}/invitations` | JWT(ADMIN) | body `{type, role, email?, expiresInHours?}` |
| GET    | `/api/v1/workspaces/{id}/invitations` | JWT(ADMIN) | PENDING 목록 |
| DELETE | `/api/v1/workspaces/{id}/invitations/{invId}` | JWT(ADMIN) | revoke |
| GET    | `/api/v1/invitations/preview?token=...` | **공개** | 워크스페이스 이름/role/만료/usable |
| POST   | `/api/v1/invitations/accept` | JWT | body `{token}` → 멤버로 합류 |

### 응답 본문 핵심
- `WorkspaceMemberResponse.profileHref = "/users/{userId}"` — FE의 "프로필 버튼"이 이 경로로 navigate.
- `CreateInvitationResponse.inviteToken/inviteUrl` — **LINK 타입에만** 채워짐. EMAIL 타입은 토큰을 응답으로 노출하지 않고 이메일 본문에만 포함.

---

## 7. 예외 매핑 (`WorkspaceExceptionHandler` + 기존 핸들러)

| 예외 | HTTP | 응답 본문 |
|---|---|---|
| `WorkspaceAccess.AccessDeniedException` | 403 | ProblemDetail(message) |
| `AcceptInvitationUseCase.InvalidInvitationException` | 410 Gone | ProblemDetail(message) |
| `NoSuchElementException` | 404 | (auth ExceptionHandler) |
| `IllegalArgumentException` | 400 | (auth ExceptionHandler) |
| `IllegalStateException` | 401 | (auth ExceptionHandler) ← 마지막 ADMIN 보호 같은 도메인 위반은 의미상 409가 더 적절. 추후 분리 검토 |

---

## 8. End-to-End 시나리오

### 8.1 워크스페이스 개설 + 첫 초대(이메일)
```
1) FE → POST /api/v1/workspaces { "name": "Backend Squad" }
   ← 201 + WorkspaceResponse(viewerRole=ADMIN)

2) 사용자가 멤버 화면에서 "이메일로 초대" 선택
   FE → POST /api/v1/workspaces/{id}/invitations
        { "type":"EMAIL", "role":"WRITE", "email":"alice@x.com", "expiresInHours":48 }
   ← 201 + { invitationId, type:"EMAIL", role:"WRITE", expiresAt, inviteToken:null, inviteUrl:null }

3) 백엔드: alice@x.com에게 이메일 전송 (현재는 로그)
4) Alice가 이메일의 acceptUrl 클릭 → FE 라우트가 token으로:
   - GET /api/v1/invitations/preview?token=... (비로그인도 가능, 워크스페이스 이름 미리보기)
   - 로그인 후 POST /api/v1/invitations/accept { "token": "..." }
   ← AcceptInvitationResponse(workspaceId, role)
```

### 8.2 링크 초대(누구나 클릭)
```
1) FE → POST /api/v1/workspaces/{id}/invitations
        { "type":"LINK", "role":"READ" }
   ← 201 + { inviteUrl: "https://app/.../invitations/<token>", inviteToken: "<token>" }
2) FE가 inviteUrl을 사용자에게 노출(복사 버튼 등)
3) 받는 쪽: §8.1의 4)와 동일한 preview/accept 흐름
```

### 8.3 멤버 목록 + 프로필 이동
```
1) FE → GET /api/v1/workspaces/{id}/members
   ← [{ userId, displayName, avatarUrl, role, joinedAt, profileHref:"/users/{userId}" }, ...]
2) 각 행 옆 [Profile] 버튼 클릭 → router.push(profileHref)
```

### 8.4 권한 변경 / 추방
```
PATCH /api/v1/workspaces/{id}/members/{userId}  body: { "role": "WRITE" }
DELETE /api/v1/workspaces/{id}/members/{userId}
```
마지막 ADMIN의 강등/추방 시도 시 401(IllegalState 매핑) — 이 부분은 `WorkspaceExceptionHandler`에서 409로 분리 가능.

### 8.5 워크스페이스 떠나기
```
DELETE /api/v1/workspaces/{id}/members/me  → 204
```
혼자 남은 ADMIN은 떠날 수 없음.

---

## 9. 차후 작업 (in-scope 미구현)

- **이메일 발송 어댑터**: SES/SendGrid 어댑터 추가, 템플릿 엔진 도입.
- **`maxMembersPerWorkspace` 강제 적용**: 현재 프로퍼티만 정의되어 있고 가드는 미구현.
- **IllegalStateException 세분화**: 마지막 ADMIN 보호는 409 Conflict로 분리.
- **도메인 이벤트**: `WorkspaceCreated`, `MemberJoined`, `MemberRoleChanged` 등을 이벤트로 발행 → noti-api/insight-api 연동 자리.
- **워크스페이스 Slug**: `iam_workspace.slug` UNIQUE 추가 시 URL을 `/workspaces/{slug}`로 운영 가능.
- **Audit log**: 누가 누구를 언제 invite/promote/remove 했는지 별도 로그.

---

## 10. 보안 위협 매핑

| 위협 | 방어 |
|---|---|
| 토큰 DB 유출 | SHA-256 해시만 저장(`HashUtils`) |
| 링크 초대 무한 재사용 | accept 시 `status=ACCEPTED`로 1회용 처리 |
| 토큰 무한 유효 | TTL + `markExpiredIfNeeded()` + 만료 시 PENDING→EXPIRED |
| 외부에서 ADMIN 탈취 | 초대로 ADMIN 부여 차단(도메인 init + usecase) |
| 다른 사람 이메일 초대를 도용 | EMAIL 초대는 수락자 계정 이메일 일치 검증 |
| 비멤버의 정보 열람 | 모든 read 유스케이스에 `requireMember`/`requireAdmin` 가드 |
| 워크스페이스 무단 삭제/수정 | ADMIN 가드 |
| 마지막 ADMIN 사라짐 → 워크스페이스 좀비 | role count 체크로 차단 |
| race로 동일 멤버 중복 INSERT | `(workspace_id, user_id)` UNIQUE |
| 토큰 충돌 | `token_hash` UNIQUE + 256-bit 엔트로피 |
