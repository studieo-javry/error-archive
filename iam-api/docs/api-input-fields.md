# Client → Server 입력 필드 종합표 (iam-api)

> 최종 갱신: 2026-06-05
> iam-api 의 모든 HTTP endpoint 가 클라이언트에서 받는 **모든 입력 필드** 를 *위치(path/query/body/cookie)*, *타입*, *필수 여부*, *null 허용*, *검증 제약*, *예시* 로 명시.
> Swagger UI(`/swagger-ui.html`) 와 동등 정보 — 한 페이지로 비교 가능.

---

## 0. TL;DR · 표기법

- **7 controller · 29 endpoint** 정리 (auth-oauth 2 / auth-token 2 / users-me 6 / social-follow 5 / workspaces 5 / workspace-invitations 5 / workspace-members 4)
- **인증 패턴 (iam-api 만의 특이점)**:
  - 대부분 endpoint: `Authorization: Bearer <user JWT>` 헤더. principal=`Jwt` 객체에서 `jwt.subject` 로 userId 추출
  - OAuth 흐름 (start/callback) + Token refresh / logout + 일부 public GET: **인증 불필요** (`@SecurityRequirements` 로 표시)
  - refresh / logout: `Authorization` 대신 `Cookie: <refresh-token>` 으로 인증
  - **core-api 와 다름** — core-api 는 `X-Internal-Auth` (게이트웨이가 발급) 사용, iam-api 는 사용자 JWT 직접 검증
- **표기**:

| 기호 | 의미 |
|---|---|
| **필수 ✓** | `@field:NotBlank` / `@field:NotNull` / 코틀린 non-nullable + `Schema.RequiredMode.REQUIRED` 또는 path/cookie 필수 |
| **필수 ✗** | 코틀린 nullable(`?`) 또는 `default value` 보유 |
| **null OK** | 코틀린 `?` 로 `null` 값 허용 |
| `≤N` / `≥N` | `@field:Size(max=N)` / `@field:Size(min=N)` 또는 `@field:Min(N)` |
| `enum{A,B,C}` | enum 값 set |

---

## 1. auth-oauth (2 endpoints — 모두 인증 불필요)

### 1.1 `POST /api/v1/auth/oauth/{provider}/authorize` — OAuth 시작

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `provider` | path | `String` | ✓ | enum{`github`, …} | `"github"` | 소셜 프로바이더 코드 |
| `redirectUri` | body | `String` | ✓ | `NotBlank` | `"https://app.example/oauth/callback"` | provider 콜백 URI |
| `returnUrl` | body | `String?` | ✗ | null OK, **상대경로(`/...`)만**, ≤512, `//`/`://`/`\\`/공백 거부 | `"/my-page"` | 로그인 후 SPA 복귀 경로. open-redirect 방지 |

응답: 헤더에 `Set-Cookie: <oauth-state>` + (선택) `Set-Cookie: <oauth-return>`.

### 1.2 `POST /api/v1/auth/oauth/{provider}/callback` — OAuth 콜백 → 토큰 발급

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `provider` | path | `String` | ✓ | — | `"github"` | |
| `code` | body | `String` | ✓ | `NotBlank` | `"a3f1..."` | provider 가 SPA 로 돌려준 authorization code |
| `state` | body | `String` | ✓ | `NotBlank`, **state 쿠키와 상수시간 비교 일치 필요** | `"randomtoken"` | CSRF 방어 |
| `redirectUri` | body | `String` | ✓ | `NotBlank` | `"https://app.example/oauth/callback"` | authorize 단계와 동일 |
| `rememberMe` | body | `Boolean` | ✗ | 기본 `false` | `true` | true 면 refresh 토큰 persistent 쿠키 |
| `<state cookie>` | cookie | `String` | ✓ | — | | authorize 단계에서 set 한 쿠키 |
| `<return cookie>` | cookie | `String?` | ✗ | null OK | | authorize 단계의 returnUrl |

---

## 2. auth-token (2 endpoints — 인증은 refresh 쿠키)

### 2.1 `POST /api/v1/auth/refresh` — access 토큰 갱신

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `<refresh-token>` | cookie | `String` | ✓ | httpOnly 쿠키. 없거나 만료/위조면 401 |

Body 없음. 응답: 새 access 토큰 + 회전된 refresh 쿠키.

### 2.2 `POST /api/v1/auth/logout` — 로그아웃

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `<refresh-token>` | cookie | `String?` | ✗ | null OK (없어도 동작 — 멱등) |
| `Authorization` | header | `String?` | ✗ | 있으면 userId 식별에 사용 |

Body 없음. 응답 헤더로 refresh 쿠키 만료.

---

## 3. users-me (6 endpoints)

### 3.1 `GET /api/v1/users/me` — 내 프로필 조회

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `Authorization` | header | `Bearer <JWT>` | ✓ | `sub` 클레임=userId |

### 3.2 `PATCH /api/v1/users/me` — 내 프로필·환경설정 수정

Body: `application/json`. 모든 필드 nullable — null/미포함 = 유지.

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `displayName` | body | `String?` | ✗ | `≥1`, `≤100`, null=유지 | `"hongjiin"` | |
| `avatarUrl` | body | `String?` | ✗ | `≤1024`, null=유지 | | |
| `bio` | body | `String?` | ✗ | `≤280`, null=유지 | | |
| `clearAvatar` | body | `Boolean` | ✗ | 기본 `false` | | true 면 avatarUrl 명시 null 로 |
| `clearBio` | body | `Boolean` | ✗ | 기본 `false` | | |
| `language` | body | `String?` | ✗ | `≤16`, BCP 47 (도메인에서 검증), null=유지 | `"ko"` | |
| `clearLanguage` | body | `Boolean` | ✗ | 기본 `false` | | |
| `timezone` | body | `String?` | ✗ | `≤64`, IANA TZ id (도메인에서 검증), null=유지 | `"Asia/Seoul"` | |
| `clearTimezone` | body | `Boolean` | ✗ | 기본 `false` | | |
| `theme` | body | `Theme?` | ✗ | enum{`LIGHT`,`DARK`,`SYSTEM`}, null=유지 | `"DARK"` | SYSTEM = OS 따름 |
| `defaultWorkspaceId` | body | `Long?` | ✗ | null OK | `1` | 로그인 후 기본 진입 워크스페이스 |
| `clearDefaultWorkspace` | body | `Boolean` | ✗ | 기본 `false` | | |

### 3.3 `DELETE /api/v1/users/me` — 회원 탈퇴(soft)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `Authorization` | header | Bearer JWT | ✓ | 본인만. ACTIVE → PENDING_DELETION + 30일 grace |

응답 헤더로 refresh 쿠키 만료.

### 3.4 `POST /api/v1/users/me/restore` — 탈퇴 취소(복구)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `Authorization` | header | Bearer JWT | ✓ | PENDING_DELETION → ACTIVE. 멤버십·팔로우는 복원 X |

### 3.5 `GET /api/v1/users/{userId}` — 공개 프로필

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | 대상 사용자 ID |
| `Authorization` | header | Bearer JWT | ✓ | 비공개 필드 제외하고 반환. status=DELETED 면 displayName 익명화 |

### 3.6 `GET /api/v1/users/me/connections` — 내 OAuth 연결 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `Authorization` | header | Bearer JWT | ✓ | provider 내부 식별자는 노출 X |

---

## 4. social-follow (5 endpoints)

### 4.1 `PUT /api/v1/users/{userId}/follow` — 팔로우(멱등)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | 자기 자신 → 400 |
| `Authorization` | header | Bearer JWT | ✓ | |

### 4.2 `DELETE /api/v1/users/{userId}/follow` — 언팔로우(멱등)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | |
| `Authorization` | header | Bearer JWT | ✓ | |

### 4.3 `GET /api/v1/users/{userId}/follow-status` — 팔로우 상태 (public, viewer-aware)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | |
| `Authorization` | header | Bearer JWT? | ✗ | 있으면 mutual/self 표시 |

### 4.4 `GET /api/v1/users/{userId}/followers` — 팔로워 목록 (public)

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | — | |
| `page` | query | `Int` | ✗ | 기본 `0` | 0-base |
| `size` | query | `Int` | ✗ | 기본 `20` | |

### 4.5 `GET /api/v1/users/{userId}/following` — 팔로잉 목록 (public)

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `userId` | path | `Long` | ✓ | — | |
| `page` | query | `Int` | ✗ | 기본 `0` | |
| `size` | query | `Int` | ✗ | 기본 `20` | |

---

## 5. workspaces (5 endpoints)

### 5.1 `POST /api/v1/workspaces` — 워크스페이스 생성

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `name` | body | `String` | ✓ | `NotBlank`, `≥1`, `≤50` | `"team-alpha"` | 생성자 자동 ADMIN |
| `Authorization` | header | Bearer JWT | ✓ | | | |

### 5.2 `GET /api/v1/workspaces` — 내 워크스페이스 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `Authorization` | header | Bearer JWT | ✓ | 내가 멤버인 것 + 내 role |

### 5.3 `GET /api/v1/workspaces/{workspaceId}` — 상세 조회

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | 멤버(READ+) 만 |
| `Authorization` | header | Bearer JWT | ✓ | |

### 5.4 `PATCH /api/v1/workspaces/{workspaceId}` — 부분 수정

Body: `application/json`. 모든 필드 nullable — null=유지.

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | — | ADMIN 만 |
| `name` | body | `String?` | ✗ | `≥1`, `≤50`, null=유지 | |
| `notificationEnabled` | body | `Boolean?` | ✗ | null=유지 | |
| `defaultTimezone` | body | `String?` | ✗ | `≤64`, null=유지 | |

### 5.5 `DELETE /api/v1/workspaces/{workspaceId}` — 삭제

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | **생성자만** (현행) |
| `Authorization` | header | Bearer JWT | ✓ | |

---

## 6. workspace-invitations (5 endpoints)

### 6.1 `POST /api/v1/workspaces/{workspaceId}/invitations` — 초대 생성

Body: `application/json`. ADMIN 만.

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | — | | |
| `type` | body | `String` | ✓ | `NotBlank`, enum{`EMAIL`,`LINK`} | `"EMAIL"` | EMAIL=일회용, LINK=다회용 |
| `role` | body | `String` | ✓ | `NotBlank`, enum{`READ`,`WRITE`} | `"WRITE"` | **ADMIN 초대 불가** |
| `email` | body | `String?` | ✗ | `@Email` 형식, null OK | `"user@example.com"` | EMAIL 타입일 때 권장 |
| `expiresInHours` | body | `Int?` | ✗ | `≥1`, null OK | `48` | 만료까지 시간 |

### 6.2 `GET /api/v1/workspaces/{workspaceId}/invitations` — 보류 중 초대 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | ADMIN 만. PENDING 만 |

### 6.3 `DELETE /api/v1/workspaces/{workspaceId}/invitations/{invitationId}` — 철회

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | ADMIN 만 |
| `invitationId` | path | `Long` | ✓ | 멱등 |

### 6.4 `GET /api/v1/invitations/preview?token=...` — 초대 미리보기 (public)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `token` | query | `String` | ✓ | 초대 토큰. 인증 불필요 |

### 6.5 `POST /api/v1/invitations/accept` — 초대 수락

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `token` | body | `String` | ✓ | `NotBlank` | `"inv_xyz123"` | capability 토큰 |
| `Authorization` | header | Bearer JWT | ✓ | | | EMAIL 은 원자적 단일사용 |

---

## 7. workspace-members (4 endpoints)

### 7.1 `GET /api/v1/workspaces/{workspaceId}/members` — 멤버 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | 멤버(READ+) 만 |

### 7.2 `PATCH /api/v1/workspaces/{workspaceId}/members/{userId}` — 역할 변경

Body: `application/json`. ADMIN 만.

| 필드 | 위치 | 타입 | 필수 | 검증 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | — | | |
| `userId` | path | `Long` | ✓ | — | | 대상 사용자 |
| `role` | body | `String` | ✓ | `NotBlank`, enum{`READ`,`WRITE`,`ADMIN`} (또는 1/2/3) | `"WRITE"` | 마지막 ADMIN 강등 → 403 |

### 7.3 `DELETE /api/v1/workspaces/{workspaceId}/members/{userId}` — 강제 제거

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | ADMIN 만 |
| `userId` | path | `Long` | ✓ | 본인 탈퇴는 `/members/me` |

### 7.4 `DELETE /api/v1/workspaces/{workspaceId}/members/me` — 본인 탈퇴

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `workspaceId` | path | `Long` | ✓ | 마지막 ADMIN 이면 거부 |

---

## 8. 공통 인증 (iam-api)

| 경로 | 인증 방식 |
|---|---|
| OAuth start/callback (`/api/v1/auth/oauth/**`) | **없음** (public) |
| Token refresh/logout (`/api/v1/auth/refresh`, `/logout`) | **refresh 쿠키** (httpOnly) |
| Public GET (follow-status, followers, following, invitations/preview) | **없음** — 인증 헤더 있으면 viewer-aware |
| 그 외 모든 endpoint | **`Authorization: Bearer <user JWT>`** |

JWT 검증: iam-api 가 자기 발급한 access 토큰 — `sub` claim 에서 userId 추출. core-api 의 `X-Internal-Auth` 와는 *다른* 체계.

---

## 9. 검증된 모든 enum 값 (참조용)

| Enum | 값 | 위치 |
|---|---|---|
| `Theme` | `LIGHT` / `DARK` / `SYSTEM` | UpdateMyProfileRequest |
| `SocialProvider` | `github` / … | OAuth path |
| `InvitationType` | `EMAIL` / `LINK` | CreateInvitationRequest.type |
| `WorkspaceRole` | `READ` / `WRITE` / `ADMIN` | ChangeMemberRoleRequest.role / CreateInvitationRequest.role (단 ADMIN 초대 X) |

---

## 10. Swagger 어노테이션 보완 권고 (현재 누락)

| # | 위치 | 누락 / 문제 | 우선순위 |
|---|---|---|---|
| 1 | `auth/.../StartOAuthRequest.kt` | 두 필드 모두 `@Schema(description/example)` 부재. returnUrl 의 검증 규칙(상대경로만, ≤512 등)이 swagger 에 안 보임 | HIGH |
| 2 | `auth/.../OAuthCallbackRequest.kt` | 4 필드 전부 `@Schema` 부재 | HIGH |
| 3 | `workspace/.../CreateWorkspaceRequest.kt` | `name` 에 `@Schema` 부재 | HIGH |
| 4 | `workspace/.../UpdateWorkspaceRequest.kt` | 3 필드 전부 `@Schema` 부재 | HIGH |
| 5 | `workspace/.../CreateInvitationRequest.kt` | `type`/`role` 이 enum 인데 `String` 으로 받음 — `@Schema(allowableValues=["EMAIL","LINK"])` / `["READ","WRITE"]` 명시 권장. `email` 의 `@Schema` 부재 | HIGH |
| 6 | `workspace/.../AcceptInvitationRequest.kt` | `token` 에 `@Schema` 부재 | HIGH |
| 7 | `workspace/.../ChangeMemberRoleRequest.kt` | `role` enum 인데 `String` — `@Schema(allowableValues=["READ","WRITE","ADMIN"])` 명시 권장 | HIGH |
| 8 | `auth/.../UpdateMyProfileRequest.kt` | 매우 잘 갖춤 | OK |
| 9 | `FollowController.followers/following` | `page` 에 `@Min(0)`, `size` 에 `@Min(1)/@Max(100)` 권장 (현재 검증 없음 — 음수/큰 값 가능) | MED |
| 10 | `OAuthController.authorize` 의 `provider` path | `@Parameter(allowableValues=...)` 명시 (현재 String 받음) | LOW |

### 다음 단계

- (HIGH) DTO 6개에 `@field:Schema(description, example, requiredMode)` 추가
- (HIGH) enum-as-String 필드에 `@Schema(allowableValues=[...])` 명시
- (MED) followers/following 의 `page`/`size` 범위 검증

---

## 11. 관련 문서

- core-api 의 같은 종합표: [`../../core-api/docs/api-input-fields.md`](../../core-api/docs/api-input-fields.md)
- 변경 이력: 루트 [`docs/changelog.md`](../../docs/changelog.md)
