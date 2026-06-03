# iam-api Auth Implementation Reference

GitHub OAuth 소셜 로그인 + JWT(Access/Refresh) 인증 모듈의 모든 구성 요소를 한 곳에서 정리한 문서.

---

## 0. 디렉터리 한눈에 보기

```
src/main/kotlin/org/studieojavry/iamapi/
├── auth/
│   ├── domain/
│   │   └── model/
│   │       ├── User.kt
│   │       ├── SocialIdentity.kt
│   │       ├── RefreshToken.kt
│   │       └── vo/
│   │           ├── Email.kt
│   │           ├── SocialProvider.kt
│   │           └── UserStatus.kt
│   ├── application/
│   │   ├── command/
│   │   │   ├── StartOAuthFlowCommand.kt
│   │   │   ├── SocialLoginCommand.kt
│   │   │   └── RefreshAccessTokenCommand.kt
│   │   ├── port/
│   │   │   ├── UserRepositoryPort.kt
│   │   │   ├── SocialIdentityRepositoryPort.kt
│   │   │   ├── RefreshTokenRepositoryPort.kt
│   │   │   ├── SocialProfileFetcherPort.kt
│   │   │   ├── AuthorizationUrlBuilderPort.kt
│   │   │   ├── JwtIssuerPort.kt
│   │   │   └── SecureRandomPort.kt
│   │   └── usecase/
│   │       ├── StartOAuthFlowUseCase.kt
│   │       ├── SocialLoginUseCase.kt
│   │       ├── RefreshAccessTokenUseCase.kt
│   │       └── LogoutUseCase.kt
│   ├── presentation/web/
│   │   ├── OAuthController.kt
│   │   ├── TokenController.kt
│   │   ├── UserController.kt
│   │   ├── AuthExceptionHandler.kt
│   │   └── dto/
│   │       ├── request/StartOAuthRequest.kt
│   │       ├── request/OAuthCallbackRequest.kt
│   │       ├── response/AuthorizeUrlResponse.kt
│   │       ├── response/TokenResponse.kt
│   │       └── response/MyProfileResponse.kt
│   ├── infrastructure/
│   │   ├── jpa/
│   │   │   ├── UserEntity.kt / UserJpaRepository.kt / UserRepositoryAdapter.kt
│   │   │   ├── SocialIdentityEntity.kt / ...JpaRepository.kt / ...Adapter.kt
│   │   │   └── RefreshTokenEntity.kt / ...JpaRepository.kt / ...Adapter.kt
│   │   ├── oauth/
│   │   │   ├── RestClientConfig.kt
│   │   │   ├── GithubAuthorizationUrlBuilder.kt
│   │   │   └── GithubProfileFetcher.kt
│   │   ├── jwt/
│   │   │   ├── NimbusJwtIssuerAdapter.kt
│   │   │   └── JwtDecoderConfig.kt
│   │   ├── random/SecureRandomAdapter.kt
│   │   └── security/
│   │       ├── SecurityConfig.kt
│   │       └── AuthCookieFactory.kt
│   └── config/
│       ├── JwtProperties.kt
│       ├── OAuthProperties.kt
│       ├── AuthCookieProperties.kt
│       └── AuthConfigRegistration.kt
└── shared/util/HashUtils.kt
```

---

## 1. 도메인 계층 (`auth/domain/model`)

순수 비즈니스 객체. Spring/JPA에 의존하지 않음.

### `vo/Email`
| 항목 | 내용 |
|---|---|
| 역할 | 이메일 형식·길이(<=254) 검증된 value class. |
| 사용 | `Email("foo@bar.com")` — 형식 위반 시 즉시 `IllegalArgumentException`. |

### `vo/SocialProvider`
| 항목 | 내용 |
|---|---|
| 역할 | `GITHUB`, `GOOGLE`, `APPLE` enum. |
| 핵심 | `SocialProvider.fromCode("github")` — 대소문자 무시, 미지원 값은 예외. |

### `vo/UserStatus`
| 항목 | 내용 |
|---|---|
| 역할 | `ACTIVE`/`SUSPENDED`/`DELETED`. |
| 핵심 | `canSignIn()` — `ACTIVE`만 로그인 가능. |

### `User`
| 항목 | 내용 |
|---|---|
| 역할 | 사용자 애그리거트 루트. private 생성자 + 정적 팩토리. |
| 필드 | `id`, `email`, `displayName`, `avatarUrl`, `bio`, `status`, `createdAt`, `updatedAt`. |
| 핵심 | `User.create(email, displayName, avatarUrl)` (신규, bio는 null로 시작), `User.rehydrate(...)` (영속 → 도메인). |
| 동작 | `changeDisplayName`, `changeAvatar`, `changeBio` — null/동일성 체크 + `updatedAt` 갱신. |
| 검증 | displayName 1~100자, avatarUrl `http(s)://` prefix + 1024자 이하, bio 280자 이하 (trim 후 빈 문자열은 null로). |
| 가드 | `ensureSignInAllowed()` — 비활성 상태면 `IllegalStateException`. |

### `SocialIdentity`
| 항목 | 내용 |
|---|---|
| 역할 | `(provider, providerUserId) → userId` 매핑. 한 유저가 여러 provider 연동 가능. |
| 핵심 | `SocialIdentity.link(userId, provider, providerUserId, email, profileUrl)`. |

### `RefreshToken`
| 항목 | 내용 |
|---|---|
| 역할 | 리프레시 토큰의 **해시**·family·rememberMe 정책·만료·revoke 상태를 표현. |
| 필드 | `id`, `userId`, `familyId`, `tokenHash`, `rememberMe`, `expiresAt`, `createdAt`, `revokedAt`, `replacedByHash`. |
| 핵심 | `issue(rememberMe)` — 신규 발급. `revoke(replacedByHash)` — 회전 시 이전 토큰 폐기. `isUsable()` — 만료/폐기 종합 판단. |
| 보안 | DB에는 평문 토큰을 절대 저장하지 않음. 해시만 저장. |
| rememberMe 보존 | rotation 시 동일 값 승계 → 한 세션 내내 일관된 TTL/쿠키 정책 유지. |

---

## 2. 애플리케이션 계층 (`auth/application`)

### 2.1 Commands (`command/`)
유스케이스의 입력 DTO.

| 클래스 | 필드 |
|---|---|
| `StartOAuthFlowCommand` | `provider`, `redirectUri` |
| `SocialLoginCommand` | `provider`, `credential: ProviderCredential` |
| `RefreshAccessTokenCommand` | `refreshToken` |

### 2.2 Ports (`port/`)
유스케이스가 의존하는 인터페이스. 어댑터(infrastructure)가 구현.

| 포트 | 책임 | 구현체 |
|---|---|---|
| `UserRepositoryPort` | User 영속화. | `UserRepositoryAdapter` |
| `SocialIdentityRepositoryPort` | provider+providerUserId 조회/저장. | `SocialIdentityRepositoryAdapter` |
| `RefreshTokenRepositoryPort` | 토큰 저장·해시 조회·family 폐기. | `RefreshTokenRepositoryAdapter` |
| `SocialProfileFetcherPort` | OAuth 코드/ID 토큰 → 사용자 프로필. **provider별 전략**. | `GithubProfileFetcher` (추후 `GoogleProfileFetcher`, `AppleProfileFetcher`) |
| `AuthorizationUrlBuilderPort` | provider별 인가 URL 생성. | `GithubAuthorizationUrlBuilder` |
| `JwtIssuerPort` | Access JWT 서명·발급. | `NimbusJwtIssuerAdapter` |
| `SecureRandomPort` | URL-safe Base64 랜덤 토큰. | `SecureRandomAdapter` |

`SocialProfileFetcherPort.ProviderCredential`:
- `AuthorizationCode(code, redirectUri)` — 웹 OAuth 표준 플로우.
- `IdToken(idToken)` — 모바일 네이티브(Apple/Google Sign-In)용.

### 2.3 Use Cases (`usecase/`)

#### `GetMyProfileUseCase` (`@Transactional(readOnly = true)`)
- 입력: `userId: Long`
- 처리: `UserRepositoryPort.findById(userId)` 후 프로필 필드 추출.
- 출력: `Result(userId, email, displayName, avatarUrl, bio, status)` — 없으면 `NoSuchElementException`.

#### `UpdateMyProfileUseCase` (`@Transactional`)
- 입력: `UpdateMyProfileCommand(userId, displayName?, avatarUrl?, bio?, clearAvatar, clearBio)`
- 처리: null이면 미변경, `clear*` 플래그면 null로 설정. 도메인의 `changeDisplayName/changeAvatar/changeBio`를 통과해야 하므로 길이/URL 형식 검증이 자동 적용됨.
- 출력: `Result(userId, email, displayName, avatarUrl, bio, status)`.

#### `StartOAuthFlowUseCase`
- 입력: `StartOAuthFlowCommand`
- 처리:
  1. `provider`에 매칭되는 `AuthorizationUrlBuilderPort` 선택.
  2. `SecureRandomPort`로 256-bit state 생성.
  3. provider authorize URL 빌드.
- 출력: `Result(authorizationUrl, state)` — state는 컨트롤러가 HttpOnly 쿠키로 저장.

#### `SocialLoginUseCase` (`@Transactional`)
- 입력: `SocialLoginCommand`
- 처리:
  1. provider에 맞는 `SocialProfileFetcherPort`로 프로필 획득.
  2. `(provider, providerUserId)`로 `SocialIdentity` 조회.
     - 있으면: 연결된 `User` 로드 + `ensureSignInAllowed()`. 또한 **`User.avatarUrl`이 null이고 provider에서 받아온 avatar가 있으면** `changeAvatar`로 보강 후 저장(이미 있는 avatar는 사용자 커스터마이징 보호 차원에서 덮어쓰지 않음).
     - 없으면: `User.create(email, displayName, avatarUrl)` + `SocialIdentity.link()` 신규 저장.
  3. Access JWT 서명 + 256-bit Refresh 토큰 생성 → 해시만 DB 저장 (`familyId = UUID.randomUUID()`).
- 출력: `Result(userId, accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt)` — Refresh 평문은 이때만 외부로 나감.

#### `RefreshAccessTokenUseCase` (`@Transactional`)
- 입력: `RefreshAccessTokenCommand(refreshToken)`
- 처리 (회전 + 재사용 탐지):
  1. 제시된 토큰을 SHA-256 해시 → DB 조회. 없으면 `InvalidRefreshTokenException`.
  2. 이미 `revoked` 상태 → **재사용 공격 의심**, `revokeFamily(familyId)`로 가족 전체 폐기 + 예외.
  3. 만료 → 예외.
  4. 새 Refresh 토큰 발급(같은 family) + 기존 토큰 `revoke(replacedByHash=newHash)`.
  5. 새 Access JWT 발급.
- 출력: `Result(...)` — 새 access/refresh 페어.

#### `LogoutUseCase` (`@Transactional`)
- 입력: `refreshToken?`, `userId?`
- 처리:
  - refreshToken 제시되면 해당 family 전체 폐기.
  - userId 제시되면(인증된 요청) 해당 사용자의 모든 미폐기 토큰 폐기.

---

## 3. Presentation 계층 (`auth/presentation/web`)

### 컨트롤러 매핑

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/v1/auth/oauth/{provider}/authorize` | 불필요 | state 발급 + 인가 URL 반환 |
| `POST` | `/api/v1/auth/oauth/{provider}/callback`  | 불필요 | code 교환 → 로그인 → 토큰 발급 |
| `POST` | `/api/v1/auth/refresh` | 쿠키 | access 재발급 + refresh 회전 |
| `POST` | `/api/v1/auth/logout`  | 선택(JWT) | family 폐기, refresh 쿠키 만료 |
| `GET`   | `/api/v1/users/me` | JWT 필수 | 본인 프로필 조회 (id/email/displayName/avatarUrl/bio/status) |
| `PATCH` | `/api/v1/users/me` | JWT 필수 | 프로필 수정 (displayName/avatarUrl/bio + clearAvatar/clearBio) |

### `OAuthController`
- **`authorize`**: `StartOAuthRequest{redirectUri}` → 응답 `AuthorizeUrlResponse{authorizationUrl}` + `Set-Cookie: iam_oauth_state=...; HttpOnly; Secure; Path=/api/v1/auth/oauth; Max-Age=300`.
- **`callback`**: `OAuthCallbackRequest{code, state, redirectUri}` →
  1. `iam_oauth_state` 쿠키와 body의 `state`를 `MessageDigest.isEqual`(상수시간)로 비교.
  2. 일치하면 state 쿠키 만료시키고 `SocialLoginUseCase` 호출.
  3. 응답: 본문에 `TokenResponse`, 헤더에 `Set-Cookie: iam_refresh=...; HttpOnly; Secure; SameSite=Lax`.

### `TokenController`
- **`refresh`**: `iam_refresh` 쿠키만 읽음. body 없음. 새 access/refresh 회전 후 응답.
- **`logout`**: 쿠키 + (있으면) JWT principal subject로 토큰 폐기. `iam_refresh` 만료 쿠키 세팅 후 `204 No Content`.

### `UserController`
- **`GET /me`**: `@AuthenticationPrincipal Jwt`에서 `sub` 추출 → `GetMyProfileUseCase` 호출 → `MyProfileResponse` 반환. JWT 검증은 `oauth2ResourceServer` 필터가 담당하므로 컨트롤러에서는 subject 형식만 검사.

### `AuthExceptionHandler`
| 예외 | HTTP | 응답 |
|---|---|---|
| `InvalidRefreshTokenException` | 401 | `ProblemDetail("invalid_refresh_token")` |
| `IllegalArgumentException` | 400 | `ProblemDetail(message)` |
| `IllegalStateException` | 401 | `ProblemDetail("authentication_failed")` |

---

## 4. Infrastructure 계층

### 4.1 JPA (`infrastructure/jpa`)

| 테이블 | 엔티티 | 핵심 제약 |
|---|---|---|
| `iam_user` | `UserEntity` | `email` 인덱스, `avatar_url VARCHAR(1024)` |
| `iam_social_identity` | `SocialIdentityEntity` | `(provider, provider_user_id)` UNIQUE, `user_id` 인덱스 |
| `iam_refresh_token` | `RefreshTokenEntity` | `token_hash` UNIQUE, `user_id`/`family_id` 인덱스 |

각 Entity는 `toDomain()`/`fromDomain()`로 도메인 객체와 양방향 매핑. `RefreshTokenJpaRepository`는 `revokeFamily`/`revokeAllByUserId`를 `@Modifying @Query`로 일괄 처리.

### 4.2 OAuth (`infrastructure/oauth`)

| 클래스 | 책임 |
|---|---|
| `RestClientConfig` | OAuth 외부 호출 전용 `RestClient` 빈(`oauthRestClient`, 5초 read timeout). |
| `GithubAuthorizationUrlBuilder` | GitHub authorize URL 빌드(`scope=read:user user:email`, `state` 포함). |
| `GithubProfileFetcher` | code → access token 교환 → `/user`, `/user/emails` 호출 → `SocialProfile` 반환. **email private 케이스**(primary+verified 이메일 별도 조회) 처리. |

신규 provider 추가:
1. `auth/infrastructure/oauth/Google...` 두 클래스 추가.
2. `SocialProvider.GOOGLE`은 이미 정의됨 — Spring이 자동으로 컬렉션에 주입.

### 4.3 JWT (`infrastructure/jwt`)

| 클래스 | 책임 |
|---|---|
| `NimbusJwtIssuerAdapter` | HS256 서명. 클레임: `iss`, `aud`, `sub=userId`, `iat`, `nbf`, `exp`, `jti`, `typ=access`. |
| `JwtDecoderConfig` | Spring Security `JwtDecoder` 빈. 기본 검증 + `iss`/`aud`/`typ` 커스텀 validator 결합. |

### 4.4 Random (`infrastructure/random`)
`SecureRandomAdapter` — `java.security.SecureRandom` + Base64 URL-safe(no padding). 16~256 byte 길이 강제.

### 4.5 Security (`infrastructure/security`)

#### `SecurityConfig`
- CSRF disable (토큰 기반), CORS default, **STATELESS** 세션.
- 보안 헤더: `X-Content-Type-Options`, `X-Frame-Options=DENY`, `Referrer-Policy=no-referrer`, `XSS-Protection`, HSTS(1년 + includeSubDomains).
- permitAll: `/api/v1/auth/oauth/**`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/actuator/health`.
- 그 외 모두 인증 필요 (Bearer JWT).
- 401 entry point: `HttpStatusEntryPoint(UNAUTHORIZED)`.
- JWT principal claim = `sub`, authorities는 `roles` 클레임에서 `ROLE_` 접두로 매핑.

#### `AuthCookieFactory`
- `refreshTokenCookie(value, ttl, persistent)` — `iam_refresh`, HttpOnly+Secure+SameSite=Lax, path `/api/v1/auth`.
  - `persistent=true` (rememberMe ON) → maxAge가 명시된 영속 쿠키.
  - `persistent=false` (rememberMe OFF) → maxAge 미설정 = **세션 쿠키**, 브라우저 종료 시 삭제.
- `oauthStateCookie(value)` — `iam_oauth_state`, 5분, path `/api/v1/auth/oauth`.
- 각 만료 버전(`expiredXxxCookie`)도 제공.

---

## 5. Config (`auth/config`)

| 클래스 | 프로퍼티 prefix | 주요 키 |
|---|---|---|
| `JwtProperties` | `iam.jwt` | `secret`(>=64자), `issuer`, `audience`, `accessTokenTtlSeconds`(default 900), `rememberMeRefreshTtlSeconds`(default 30d), `sessionRefreshTtlSeconds`(default 8h). `refreshTtlSecondsFor(rememberMe)` 헬퍼 제공. |
| `OAuthProperties` | `iam.oauth` | `github.clientId`, `github.clientSecret`, `*.endpoint`, `scope` |
| `AuthCookieProperties` | `iam.cookie` | `refreshTokenName`, `oauthStateName`, `domain`, `secure`, `sameSite`, `path` |
| `AuthConfigRegistration` | — | 위 3개 `@EnableConfigurationProperties`로 등록 |

`@Validated` + `jakarta.validation` 어노테이션으로 부팅 시점에 누락/약한 시크릿을 차단.

---

## 6. Shared (`shared/util`)

### `HashUtils`
`sha256(String)` / `sha256(ByteArray)` — Refresh 토큰 해싱에 사용. hex 소문자 64자 반환.

---

## 7. application.yml 핵심 키

```yaml
iam:
  jwt:
    secret: ${IAM_JWT_SECRET:...}        # 운영에선 64+ 무작위 ASCII
    issuer: ${IAM_JWT_ISSUER:...}
    audience: ${IAM_JWT_AUDIENCE:...}
    access-token-ttl-seconds: 900        # 15min
    refresh-token-ttl-seconds: 1209600   # 14d
  oauth:
    github:
      client-id: ${GITHUB_OAUTH_CLIENT_ID:...}
      client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:...}
  cookie:
    secure: true
    same-site: Lax
    path: /api/v1/auth
```

---

## 8. End-to-End 사용 시나리오

### 8.1 GitHub 로그인 (웹 클라이언트)

```
1) Client → POST /api/v1/auth/oauth/github/authorize
   body: { "redirectUri": "https://app.example/oauth/github/callback" }
   ← 200 { authorizationUrl }
   ← Set-Cookie: iam_oauth_state=...

2) Client → 브라우저를 authorizationUrl로 리다이렉트
   GitHub 로그인 후 redirectUri로 ?code=...&state=... 리다이렉트

3) Client(프론트) → POST /api/v1/auth/oauth/github/callback
   body: { "code", "state", "redirectUri" }
   쿠키: iam_oauth_state=... (자동 전송)
   ← 200 { tokenType:"Bearer", accessToken, accessTokenExpiresAt, userId }
   ← Set-Cookie: iam_refresh=...; HttpOnly; Secure
   ← Set-Cookie: iam_oauth_state=; Max-Age=0

4) Client → 보호된 API 호출
   Header: Authorization: Bearer <accessToken>
```

### 8.2 Access 만료 시 회전

```
POST /api/v1/auth/refresh   (body 없음, iam_refresh 쿠키만)
← 200 { accessToken, ... }
← Set-Cookie: iam_refresh=<new>...
```
이전 refresh가 다시 등장하면 즉시 family 전체 폐기 → 강제 재로그인.

### 8.3 로그아웃

```
POST /api/v1/auth/logout
   (Authorization 옵션, iam_refresh 쿠키 옵션)
← 204
← Set-Cookie: iam_refresh=; Max-Age=0
```

### 8.4 Google/Apple 확장

| Provider | 추가할 어댑터 | 사용 Credential |
|---|---|---|
| Google (웹) | `GoogleAuthorizationUrlBuilder`, `GoogleProfileFetcher` | `AuthorizationCode` |
| Apple (iOS 네이티브) | `AppleProfileFetcher` (URL Builder 불필요) | `IdToken` (Apple JWKS 검증) |

`SocialProvider` enum은 이미 `GOOGLE`/`APPLE` 보유. Spring이 `List<SocialProfileFetcherPort>` / `List<AuthorizationUrlBuilderPort>`로 자동 주입하므로, **기존 코드 변경 없이** 어댑터 클래스만 추가하면 라우팅 완료.

---

## 9. 보안 체크리스트 매핑

| 위협 | 대응 위치 |
|---|---|
| OAuth CSRF | `OAuthController.validateState` + `AuthCookieFactory.oauthStateCookie` |
| Refresh 토큰 DB 유출 | `HashUtils.sha256` 해시만 저장 (`SocialLoginUseCase`/`RefreshAccessTokenUseCase`) |
| Refresh 탈취/재사용 | `RefreshAccessTokenUseCase` 재사용 탐지 → `revokeFamily` |
| XSS로 refresh 탈취 | `AuthCookieFactory` HttpOnly + Secure |
| CSRF로 refresh 사용 | `SameSite=Lax` + path 제한 |
| 약한 JWT 시크릿 | `JwtProperties.secret @Size(min=64)` |
| JWT 클레임 위조 | `JwtDecoderConfig` `iss`/`aud`/`typ` 검증 |
| 세션 픽세이션 | `SessionCreationPolicy.STATELESS` |
| Clickjacking | `frameOptions.deny()` |
| MITM 다운그레이드 | HSTS 1년 + includeSubDomains |
| 타이밍 공격(state 비교) | `MessageDigest.isEqual` 상수시간 비교 |