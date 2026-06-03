# iam-api Spring Security Usage Reference

iam-api에서 **Spring Security를 어떻게 활용하고 있는지**를 인증(Authentication)·인가(Authorization) 두 파트로 나눠 정리한 문서.

> 운영 정책 결정의 근거(왜 이렇게 썼는지)와 코드 위치(어디에 있는지)를 한 곳에서 추적할 수 있게 작성.
>
> 관련 문서:
> - 인증 도메인 전체 흐름 → [`auth-implementation.md`](./auth-implementation.md)
> - FE 통합 가이드 → [`auth-frontend-integration.md`](./auth-frontend-integration.md)
> - 변경 이력 → [`../../docs/changelog.md`](../../docs/changelog.md)

---

## 0. 한 줄 요약

- **검증된 인프라(JWT 검증·필터 체인·보안 헤더)는 Spring Security가, 도메인 정책(OAuth 플로우·refresh rotation·도메인 권한)은 직접 구현.**
- **stateless JWT** 모델. 세션·CSRF·formLogin 등 stateful 기능은 의도적으로 비활성화.
- **인증**: `oauth2-resource-server`(JWT) + `nimbus-jose-jwt`(서명) + 직접 구현한 OAuth flow + refresh rotation.
- **인가**: URL 단위(`authorizeHttpRequests`) + 도메인 단위(`WorkspaceAccess` 가드) 이중 적용.

---

## 1. 의존성 (`build.gradle.kts`)

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
testImplementation("org.springframework.security:spring-security-test")
```

| 의존 | 역할 | 비고 |
|---|---|---|
| `spring-boot-starter-security` | filter chain, `SecurityContextHolder`, 보안 헤더, CORS, CSRF | 핵심 |
| `spring-boot-starter-oauth2-resource-server` | JWT 디코더(`NimbusJwtDecoder`), `BearerTokenAuthenticationFilter` | 우리는 **JWT 검증만** 사용. authorization server 기능은 안 씀. |
| `spring-security-test` | `MockMvc` 보안 테스트 | 추후 테스트에서 사용 |

**의도적으로 안 쓴 것**: `spring-boot-starter-oauth2-client`. 서버 사이드 세션 OAuth login을 강제하는데, 우리는 모바일/SPA 친화적인 stateless JWT 모델이라 직접 구현이 맞음.

---

## 2. 인증 (Authentication)

> "이 요청을 보낸 사용자가 누구인가"를 결정하는 부분.

### 2.1 전체 흐름

```
[로그인 시점]
  Client → OAuthController(/api/v1/auth/oauth/github/callback)
    → SocialLoginUseCase
      → GithubProfileFetcher (OAuth code 교환·프로필 fetch)        [직접 구현]
      → User/SocialIdentity 저장
      → NimbusJwtIssuerAdapter (HS256 서명)                          [직접 구현 + nimbus-jose-jwt]
      → RefreshToken DB 저장(SHA-256 해시)                           [직접 구현]
    Response: { accessToken } + Set-Cookie: iam_refresh

[보호된 요청]
  Client → Bearer <jwt> + Cookie: iam_refresh
    → BearerTokenAuthenticationFilter (Spring Security 기본 제공)    [Spring Security]
      → JwtDecoderConfig.jwtDecoder() 검증                           [Spring Security + 우리 설정]
      → SecurityContextHolder에 Authentication 주입
    → @AuthenticationPrincipal Jwt 로 컨트롤러가 사용자 추출

[Refresh 시점]
  Client → TokenController(/api/v1/auth/refresh) + Cookie: iam_refresh
    → RefreshAccessTokenUseCase
      → SHA-256 해시로 DB 조회                                       [직접 구현]
      → revoke 감지 → family 폐기                                    [직접 구현, reuse detection]
      → 새 토큰 발급
```

### 2.2 JWT 발급 — `NimbusJwtIssuerAdapter`

위치: `auth/infrastructure/jwt/NimbusJwtIssuerAdapter.kt`

| 항목 | 값 |
|---|---|
| 알고리즘 | HS256 (`MACSigner`, 대칭키) |
| 클레임 | `iss`, `aud`, `sub`(=userId), `iat`, `nbf`, `exp`, `jti`(UUID), `typ=access` |
| 서명 키 | `JwtProperties.secret` (`@Size(min=64)` 검증, 운영 시 env 주입) |
| Header | `alg=HS256, typ=JWT` |

**Spring Security가 직접 발급하지 않는 이유**: Spring Security는 resource server 역할(검증)에 강하고, JWT 발급은 별도 라이브러리(nimbus)로 직접 하는 게 일반적. 우리도 그 패턴.

### 2.3 JWT 검증 — `JwtDecoderConfig`

위치: `auth/infrastructure/jwt/JwtDecoderConfig.kt`

```kotlin
@Bean
fun jwtDecoder(jwtProperties: JwtProperties): JwtDecoder {
    val secretKey = SecretKeySpec(jwtProperties.secret.toByteArray(UTF_8), "HmacSHA256")
    val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()

    decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
        JwtValidators.createDefault(),                                  // exp/nbf
        JwtClaimValidator("iss") { it == jwtProperties.issuer },        // 발급자
        JwtClaimValidator<List<String>>("aud") { it != null && jwtProperties.audience in it }, // 대상
        JwtClaimValidator("typ") { it == "access" }                     // refresh 토큰 혼용 차단
    ))
    return decoder
}
```

| 검증 항목 | 담당 | 의미 |
|---|---|---|
| 서명 무결성 | nimbus-jose-jwt | HS256 MAC 검증 |
| `exp`/`nbf` | `JwtValidators.createDefault()` | 만료/유효 시작 시각 |
| `iss` | 우리 커스텀 validator | 우리가 발급한 토큰만 |
| `aud` | 우리 커스텀 validator | 본 클라이언트 그룹 대상 |
| `typ=access` | 우리 커스텀 validator | refresh 토큰을 access로 오용 차단 |

**filter 자동 등록**: `oauth2-resource-server` starter가 `BearerTokenAuthenticationFilter`를 자동 등록. 우리는 `JwtDecoder` 빈만 노출하면 됨.

### 2.4 SecurityFilterChain — `SecurityConfig`

위치: `auth/infrastructure/security/SecurityConfig.kt`

```kotlin
@Bean
fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain {
    http
        .csrf { it.disable() }                                          // stateless JWT라 불필요
        .cors(Customizer.withDefaults())                                // 별도 CorsConfigurationSource 빈으로 정책
        .sessionManagement { it.sessionCreationPolicy(STATELESS) }      // 세션 생성 안 함
        .headers { ... }                                                // §4. 보안 헤더
        .authorizeHttpRequests { ... }                                  // §3.1 인가
        .exceptionHandling {
            it.authenticationEntryPoint(HttpStatusEntryPoint(UNAUTHORIZED))  // 401
        }
        .oauth2ResourceServer { rs ->
            rs.jwt { jwt ->
                jwt.decoder(jwtDecoder)
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
            }
        }
    return http.build()
}
```

### 2.5 OAuth 플로우 — 직접 구현

`spring-boot-starter-oauth2-client`(서버 사이드 세션 OAuth) 대신 직접 구현. 이유:
- stateless 모델 유지 (세션 안 씀)
- 모바일/SPA에서 동일한 백엔드 사용 가능
- state/code/token 흐름을 도메인 코드로 명시 → 테스트 가능

| 단계 | 위치 |
|---|---|
| `/authorize`: state 발급 + 인가 URL 생성 | `OAuthController.authorize` + `StartOAuthFlowUseCase` |
| GitHub redirect 후 `/callback`: state 검증 + code 교환 + 프로필 fetch + 토큰 발급 | `OAuthController.callback` + `SocialLoginUseCase` + `GithubProfileFetcher` |
| state CSRF 방지 | HttpOnly 쿠키(5분) + `MessageDigest.isEqual` 상수시간 비교 |

**Spring Security와 결합 지점**: 없음. OAuth 플로우는 비인증 endpoint(`permitAll`)에서 처리하고, 발급된 JWT만 이후 보호 endpoint에서 Spring Security가 검증.

### 2.6 Refresh Token Rotation + Reuse Detection — 직접 구현

위치: `auth/application/usecase/RefreshAccessTokenUseCase.kt`

| 동작 | 구현 방식 |
|---|---|
| 토큰 저장 | 평문 X, SHA-256 해시만 (`HashUtils.sha256`) |
| 회전 | 매 `/refresh`마다 새 토큰 발급 + 이전 토큰 즉시 revoke + `replacedByHash` 기록 |
| 재사용 감지 | revoked 토큰 재제시 → **family 전체 폐기** + 401 |
| family | `UUID` 단위. 한 로그인 세션의 회전 계보. |
| 디바이스별 격리 | 매 로그인이 새 family. 다른 디바이스 영향 없음 |

**Spring Security와 결합 지점**: 없음. `/refresh`는 `permitAll` (쿠키만 들고 호출). Spring Security가 관여하지 않는 도메인 흐름.

### 2.7 Cookie 정책 — `AuthCookieFactory`

위치: `auth/infrastructure/security/AuthCookieFactory.kt`

| 쿠키 | 정책 |
|---|---|
| `iam_refresh` | HttpOnly, Secure, SameSite=Lax, path=`/api/v1/auth`. rememberMe=true면 maxAge 명시(영속), false면 maxAge 미설정(세션 쿠키) |
| `iam_oauth_state` | 동일 정책 + path=`/api/v1/auth/oauth`, maxAge=5분 |

**Spring Security와 결합 지점**: 없음. Spring Security의 `RememberMe` 서비스는 cookie-based session 메커니즘이라 우리 모델과 안 맞음 — 우리 rememberMe는 refresh token TTL/쿠키 정책으로 직접 구현.

---

## 3. 인가 (Authorization)

> "이 사용자가 이 작업을 할 수 있는가"를 결정하는 부분.

### 3.1 URL 단위 인가 — `authorizeHttpRequests`

위치: `auth/infrastructure/security/SecurityConfig.kt`

```kotlin
.authorizeHttpRequests { auth ->
    auth.requestMatchers(
        "/api/v1/auth/oauth/**",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/actuator/health"
    ).permitAll()
        .requestMatchers(GET, "/api/v1/users/*/follow-status").permitAll()
        .requestMatchers(GET, "/api/v1/users/*/followers").permitAll()
        .requestMatchers(GET, "/api/v1/users/*/following").permitAll()
        .requestMatchers(GET, "/api/v1/invitations/preview").permitAll()
        .anyRequest().authenticated()
}
```

| 정책 | 설명 |
|---|---|
| `permitAll` | 인증 없이 호출 가능. OAuth 플로우, refresh, logout, 공개 read(팔로우/초대 미리보기), health check |
| HTTP Method 분기 | `GET`만 허용 (PUT/DELETE는 인증 필수) — 사회 그래프 read는 공개 / write는 인증 |
| `anyRequest().authenticated()` | 그 외는 모두 JWT 인증 필수 |

### 3.2 JWT subject로 사용자 식별 — `@AuthenticationPrincipal`

```kotlin
@GetMapping("/me")
fun me(@AuthenticationPrincipal jwt: Jwt): MyProfileResponse {
    val userId = jwt.subject?.toLongOrNull()
        ?: throw ResponseStatusException(UNAUTHORIZED, "invalid subject claim")
    ...
}
```

`@AuthenticationPrincipal Jwt`는 **Spring Security가 검증·주입한 JWT 객체**. 컨트롤러는 이걸로 사용자 ID를 추출.

**현재 사용처**:
- `UserController.me`, `UserController.updateMe`
- `OAuthController` (logout 시 선택)
- `TokenController.logout`
- `WorkspaceController` 전체
- `WorkspaceMemberController` 전체
- `WorkspaceInvitationController` 전체
- `FollowController` (PUT/DELETE는 필수, GET status는 선택)

### 3.3 Authorities 변환 — `JwtAuthenticationConverter`

```kotlin
private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
    val authoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
        setAuthorityPrefix("ROLE_")
        setAuthoritiesClaimName("roles")
    }
    return JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        setPrincipalClaimName("sub")
    }
}
```

- JWT의 `roles` 클레임을 `ROLE_` 접두 authority로 매핑.
- principal claim = `sub` (= userId).

**현재 활용도**: 낮음. 시스템 전역 role(예: SUPER_ADMIN)이 생기면 `@PreAuthorize("hasRole('ADMIN')")` 같은 패턴 사용 가능. 지금은 도메인 권한 위주라 §3.4가 주력.

### 3.4 도메인 단위 인가 — `WorkspaceAccess`

위치: `workspace/application/usecase/WorkspaceAccess.kt`

```kotlin
object WorkspaceAccess {
    fun requireMember(repo, workspaceId, userId): WorkspaceMember = ...
    fun requireAdmin(repo, workspaceId, userId): WorkspaceMember = ...

    class AccessDeniedException(message: String) : RuntimeException(message)
}
```

**Spring Security가 처리하지 않는 이유**:
- 권한이 **컨텍스트 의존적**(이 사용자가 *이 워크스페이스의* ADMIN인가).
- DB 조회 필요 (멤버 row + role).
- `@PreAuthorize`의 SpEL로 표현하려면 도메인이 framework에 묶임 → DDD 관점에서 비권장.

**호출 위치 (use case 진입부)**:
- `UpdateWorkspaceUseCase`, `DeleteWorkspaceUseCase` → `requireAdmin`
- `GetWorkspaceUseCase`, `ListWorkspaceMembersUseCase` → `requireMember`
- `ChangeMemberRoleUseCase`, `RemoveMemberUseCase` → `requireAdmin`
- `InviteMemberUseCase`, `ListInvitationsUseCase`, `RevokeInvitationUseCase` → `requireAdmin`

**예외 → HTTP 매핑**: `WorkspaceExceptionHandler`가 `AccessDeniedException → 403 ProblemDetail`.

### 3.5 인가 정책 정리

| 권한 단위 | 적용 위치 | 예시 |
|---|---|---|
| **인증 여부** | `SecurityConfig.authorizeHttpRequests` | `permitAll` vs `authenticated` |
| **HTTP 메서드 분기** | 동일 | `GET`만 공개, write는 인증 |
| **사용자 신원 확인** | `@AuthenticationPrincipal Jwt` | 컨트롤러가 subject로 식별 |
| **시스템 역할** (현재 미활용) | JWT `roles` 클레임 + `@PreAuthorize` 가능 | 추후 |
| **도메인 컨텍스트 권한** | `WorkspaceAccess` 가드 (use case) | "이 워크스페이스의 ADMIN인가" |

---

## 4. 보안 헤더

위치: `SecurityConfig.headers { ... }`

| 헤더 | 값 | 효과 |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | MIME sniffing 차단 |
| `X-Frame-Options` | `DENY` | clickjacking 방어 |
| `Referrer-Policy` | `no-referrer` | 외부 요청 시 referrer 누설 차단 |
| `X-XSS-Protection` | `1; mode=block` | 구형 브라우저 XSS 필터 |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | HTTPS 강제 |

**Spring Security 기본 정책에 더해 우리가 강화한 것**: `Referrer-Policy` 명시, HSTS includeSubDomains.

---

## 5. 세션 정책

| 항목 | 값 | 이유 |
|---|---|---|
| `SessionCreationPolicy` | `STATELESS` | JWT 기반. 서버는 세션 안 만듦 |
| `JSESSIONID` 쿠키 | 발급 안 됨 | stateless라 불필요 |
| CSRF 토큰 | `disable()` | 세션 기반이 아니므로 의미 없음 + JWT는 헤더로 전송 |

쿠키는 **refresh token 전용**으로만 사용 (Spring Security 세션이 아님).

---

## 6. 예외 처리

### 6.1 인증 실패 → 401
- `BearerTokenAuthenticationFilter`가 JWT 검증 실패 → `HttpStatusEntryPoint(UNAUTHORIZED)`로 401 응답.
- `RefreshAccessTokenUseCase.InvalidRefreshTokenException` → `AuthExceptionHandler`가 401 ProblemDetail.

### 6.2 인가 실패 → 403
- `WorkspaceAccess.AccessDeniedException` → `WorkspaceExceptionHandler`가 403 ProblemDetail.

### 6.3 도메인 위반 → 4xx
| 예외 | HTTP | 핸들러 |
|---|---|---|
| `IllegalArgumentException` | 400 | `AuthExceptionHandler` |
| `NoSuchElementException` | 404 | `AuthExceptionHandler` |
| `IllegalStateException` | 401 | `AuthExceptionHandler` (현재 정책, 추후 409로 분리 검토) |
| `AcceptInvitationUseCase.InvalidInvitationException` | 410 | `WorkspaceExceptionHandler` |

---

## 7. 의도적으로 안 쓰는 Spring Security 기능

| 기능 | 안 쓰는 이유 |
|---|---|
| `formLogin` | 비밀번호 인증 없음(소셜만) |
| `httpBasic` | 동일 |
| `oauth2-client` (서버 사이드 OAuth login) | stateful 세션 강제 — 우리는 stateless |
| `RememberMe` 서비스 | cookie-based session 메커니즘 — 우리 refresh 토큰 모델과 맞지 않음. rememberMe는 직접 구현 |
| `CsrfTokenRepository` | stateless + 헤더 기반 토큰이라 CSRF 표면 자체가 없음 |
| `SessionManagementConfigurer`(세션 픽세이션 등) | 세션 안 씀 |
| 인증 server (`spring-authorization-server`) | 우리는 OAuth 클라이언트 측. authorization server 역할 불필요 |

---

## 8. 마이크로서비스 분리 시 적용 방향

iam-api가 발급한 JWT를 다른 서비스(core-api 등)에서도 검증해야 함.

### 8.1 각 서비스의 필요 의존
```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
```

### 8.2 키 공유 방식
- **HS256(현재)**: 모든 서비스가 같은 secret 공유. 운영상 secret rotation 어려움.
- **권장 (분리 시점)**: RS256(비대칭). iam-api가 private key로 서명, 다른 서비스는 JWK Set endpoint(`/.well-known/jwks.json`)로 public key fetch.

### 8.3 서비스 간 호출 인증
- **사용자 JWT propagation**: gRPC interceptor에서 metadata로 전달.
- **service-to-service**: mTLS + 서비스 토큰. Spring Security 영역 밖.

---

## 9. 향후 개선 후보

### 9.1 커스텀 Principal — `CurrentUser`
현재 컨트롤러마다 `jwt.subject?.toLongOrNull()` 반복. 이를 통합:
```kotlin
data class CurrentUser(val userId: Long, val publicId: UUID)

@Component
class JwtToCurrentUserConverter : Converter<Jwt, CurrentUser> { ... }

fun me(@AuthenticationPrincipal user: CurrentUser) { ... }
```

### 9.2 `AccessDeniedHandler` 등록
현재 401만 명시적 핸들러. 403 응답 형태도 통일:
```kotlin
.exceptionHandling {
    it.authenticationEntryPoint(...)        // 401
    it.accessDeniedHandler(...)             // 403
}
```

### 9.3 RS256 마이그레이션
서비스 분리 시점. JWK Set publish.

### 9.4 워크스페이스 권한을 JWT 클레임에 포함
```json
{ "sub": "42", "ws": [{ "id": "<uuid>", "role": "ADMIN" }, ...] }
```
- core-api가 매 요청 워크스페이스 RPC 호출 없이 자체 판정.
- 권한 변경 시 family revoke로 즉시 무효화.

### 9.5 메서드 단위 `@PreAuthorize`
시스템 전역 role(예: SUPER_ADMIN)이 도입되면 사용. 도메인 컨텍스트 권한은 `WorkspaceAccess` 유지.

---

## 10. 체크리스트 — 새 endpoint 추가 시

- [ ] `permitAll` 필요한가? 그렇다면 `SecurityConfig.authorizeHttpRequests`에 명시.
- [ ] 인증된 endpoint면 컨트롤러에 `@AuthenticationPrincipal Jwt` 주입.
- [ ] 도메인 권한이 필요하면 use case 진입부에 `WorkspaceAccess.requireMember/requireAdmin` 호출.
- [ ] 예외 응답이 `AuthExceptionHandler`/`WorkspaceExceptionHandler`로 매핑되는지 확인.
- [ ] HTTP 메서드별로 다른 정책이 필요하면 `requestMatchers(GET, "...")`로 분기.

---

## 11. 한 줄 정리

**Spring Security는 우리 인증 인프라의 50%를 담당** — JWT 검증, 필터 체인, 보안 헤더, URL 인가까지. **나머지 50%(OAuth 플로우, refresh rotation, 도메인 권한)는 직접 구현해 도메인/유스케이스에 둠**. 이 분담이 stateless JWT + DDD를 모두 만족하는 균형점.
