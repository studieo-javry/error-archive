# 서비스 토폴로지 — gateway · iam-api · core-api

> error-archive 모노레포의 핵심 3개 서비스가 **어떻게 연결되어 있고**, 각자 **무엇을 책임지며**, 요청이 들어왔을 때 **어떤 흐름**으로 처리되는지 정리. 운영/디버깅 시 "어디를 봐야 하는지" 빠르게 찾기 위한 문서.

작성 시점 기준: 2026-05-07 (gateway 도입 직후, RS256/JWKS 미도입, 단일 region)

---

## 1. 큰 그림

```
                                ┌─────────────────────────────────┐
                                │     public network              │
                                │  (browser, mobile, Postman)     │
                                └────────────────┬────────────────┘
                                                 │  Bearer JWT
                                                 ▼
                              ┌──────────────────────────────────────┐
                              │  gateway:8000  (Spring Boot 4)       │
                              │  ─────────────────────────────────   │
                              │  ◯ JwtAuthFilter                     │
                              │     - public path skip               │
                              │     - HS256 validation               │
                              │       (Share secret with iam-api,    │
                              │       iss/aud/exp/nbf validation)    │
                              │     - add X-User-Id, X-Roles header  │
                              │  ◯ ProxyController                   │
                              │     - prefix matching → downstream   │
                              │     - Forward to JDK HttpClient      │
                              └────┬────────────────────┬────────────┘
                                   │                    │
                                   │  X-User-Id +       │  X-User-Id +
                                   │  Authorization     │  Authorization
                                   │  (Deliver just as) │  (Deliver just as)
                                   ▼                    ▼
                    ┌──────────────────────┐  ┌─────────────────────────┐
                    │  iam-api:8080        │  │  core-api:8081          │
                    │  ──────────────────  │  │  ───────────────────    │
                    │  ◯ SecurityConfig    │  │  ◯ Spring Security X    │
                    │     -JWT Self-Verify │  │     (no depdendency)    │
                    │     (defense-in-     │  │  ◯ Trust X-User-Id      │
                    │      depth)          │  │    header only          │
                    │  ◯ issue OAuth, JWT  │  │  ◯ ErrorCase            │
                    │  ◯ User/Workspace    │  │    domain logic         │
                    │                      │  │                         │
                    └──────────┬───────────┘  └────────────┬────────────┘
                               │                           │
                               │            ┌──────────────┘
                               │            │  (token relay:
                               │            │   현재 요청의
                               │            │   Authorization
                               │            │   forwarding)
                               ▼            ▼
                    ┌──────────────────┐  ┌──────────────────┐
                    │  iam_db          │  │  core_db         │
                    │  PG :5432        │  │  PG :5433        │
                    └──────────────────┘  └──────────────────┘
```

핵심 원칙 3가지:
1. **외부 트래픽은 무조건 gateway 통과** — 내부 서비스(8080/8081)는 외부 직접 노출 금지 (운영에서는 VPC 격리)
2. **검증은 gateway 단독 책임** — core-api 는 인증 코드 0줄, iam-api 만 자체 검증 추가 보유 (방어적 중복)
3. **서비스 간 호출은 token relay** — core-api → iam-api 호출 시 **요청자의** Authorization 을 그대로 전달. 별도 M2M 토큰 없음(아직).

---

## 2. 각 서비스의 단일 책임

### gateway
**역할**: API 진입점 + 인증 관문.

| 책임 | 구현 위치 |
|-----|----------|
| JWT 검증 (HS256, iss/aud/exp/nbf) | `config/JwtDecoderConfig.kt` |
| public path 통과 (로그인/health 등) | `filter/JwtAuthFilter.kt` + `gateway.public-paths` yml |
| 검증 성공 시 X-User-Id, X-Roles 주입 | `filter/JwtAuthFilter.kt` |
| 검증 실패 시 즉시 401 (downstream 호출 X) | `filter/JwtAuthFilter.kt#unauthorized` |
| prefix 매칭 라우팅 | `proxy/ProxyController.kt` + `gateway.routes` yml |
| hop-by-hop 헤더 정리 (transfer-encoding 등) | `proxy/ProxyController.kt#hopByHop` |

**책임이 아닌 것**:
- 비즈니스 로직 ❌
- 인가(권한 체크) ❌ — 인증만, 인가는 downstream
- 사용자 정보 영속 ❌
- 토큰 발급 ❌ (그건 iam-api)

**왜 Spring Cloud Gateway 가 아니라 plain MVC + JDK HttpClient?**
- Spring Boot 4 와 Spring Cloud 호환 release 가 아직 표준화 전 단계
- 현재 필요 기능(인증 + prefix 라우팅)은 100줄 이내로 구현 가능
- 트래픽이 커지거나 rate-limit/circuit-breaker 등 횡단 관심사가 늘면 SCG 또는 Kong 으로 이전 검토

### iam-api
**역할**: "누가 누구인지" 결정 + 자격증명 발급/회수.

| 책임 | 구현 위치 |
|-----|----------|
| OAuth (GitHub) 콜백 처리 | `auth/presentation/web/OAuthController.kt` |
| JWT access token 발급 (HS256) | `auth/infrastructure/jwt/NimbusJwtIssuerAdapter.kt` |
| Refresh token 회전/만료 (HttpOnly cookie + DB hash) | `auth/application/usecase/RefreshAccessTokenUseCase.kt` |
| 로그아웃 (refresh 무효화) | `auth/application/usecase/LogoutUseCase.kt` |
| User / SocialIdentity 도메인 | `auth/domain/model/` |
| Workspace 멤버십 / 초대 도메인 | `workspace/` |

**자체 JWT 검증을 유지하는 이유** (defense-in-depth):
- 누군가 gateway 를 우회해 직접 8080 을 쳐도 차단
- 운영에서 VPC 격리가 무너졌을 때의 마지막 방어선
- 코스트는 거의 0 (HS256 검증 ≈ 마이크로초)

### core-api
**역할**: 에러 케이스 도메인 로직.

| 책임 | 구현 위치 |
|-----|----------|
| ErrorCase 생성 (snapshot 추출, fingerprint 산정, marker 발급) | `errorcase/application/usecase/CreateErrorCaseUseCase.kt` |
| Attachment 업로드 (로컬 FS 임시) | `errorcase/application/usecase/CreateAttachmentUseCase.kt` |
| 도메인 모델 (`ErrorCase`, `Attachment`, `CodeSnippet`) | `errorcase/domain/model/` |
| iam-api 호출 (워크스페이스 권한 검증) | `errorcase/infrastructure/iam/IamWorkspaceQueryAdapter.kt` |

**인증 코드가 없음**:
- `build.gradle.kts` 에 `spring-boot-starter-security` 미포함
- `@AuthenticationPrincipal Jwt` 같은 코드 없음
- 컨트롤러는 `@RequestHeader("X-User-Id") userId: Long` 으로 신원 수신
- gateway 우회 시 = 인증 통째로 통과 → **VPC 격리/네트워크 정책으로 보장 필수**

---

## 3. 통신 패턴

### 3.1 동기 HTTP — gateway → downstream
- 프로토콜: HTTP/1.1
- 클라이언트: JDK `HttpClient` (java.net.http)
- 타임아웃: connect 5s, read 30s
- redirect: NEVER (gateway 가 응답 그대로 통과)
- 검증된 헤더(X-User-Id, X-Roles)와 hop-by-hop 제외 모든 헤더를 그대로 전달
- 응답 body: byte 단위 그대로 전달 (스트리밍 X — 작은 페이로드 가정)

### 3.2 동기 HTTP — core-api → iam-api (token relay)
- 클라이언트: Spring `RestClient` + interceptor
- 인증 모드: **요청자의 토큰을 그대로 forward**
  ```kotlin
  // IamApiRestClientConfig.kt
  .requestInterceptor { request, body, execution ->
      currentRequest()?.let { incoming ->
          incoming.getHeader("X-User-Id")?.let { request.headers.setIfAbsent("X-User-Id", it) }
          incoming.getHeader("Authorization")?.let { request.headers.setIfAbsent("Authorization", it) }
      }
      execution.execute(request, body)
  }
  ```
- 즉, **사용자가 요청 → gateway 검증 → core-api 가 받음 → 같은 사용자 자격으로 iam-api 호출**
- iam-api 는 자체 JWT 검증으로 또 한 번 신원 확인

이 패턴의 한계:
- 사용자가 로그인하지 않은 상태에서 발생한 system 작업은 호출 불가 (예: 백그라운드 잡)
- 미래 → service-account 토큰 (M2M) 도입 필요

### 3.3 통신 안 하는 것
- ❌ DB 공유 (각 서비스 자체 PG 보유)
- ❌ 메시지 큐 (아직 비동기 통신 없음, outbox 패턴 미도입)
- ❌ gRPC (HTTP/JSON 만 사용)

---

## 4. 토큰 라이프사이클 (Login → Refresh → Logout)

### 4.1 로그인 (OAuth start)
```
[브라우저] POST /api/v1/auth/oauth/github/authorize
              { "redirectUri": "http://localhost:5173/oauth/callback" }
   │
   ▼
[gateway:8000]
   │  ◯ /api/v1/auth/oauth 는 publicPath → JWT 검증 skip
   │  ◯ ProxyController → http://localhost:8080
   ▼
[iam-api:8080]
   │  ◯ state 토큰 발급 (URL-safe)
   │  ◯ Set-Cookie: iam_oauth_state=<state>; HttpOnly; Path=/api/v1/auth/oauth
   │  ◯ Body: { authorizationUrl: "https://github.com/login/oauth/authorize?..." }
   ▼
[gateway] 응답 그대로 통과 (Set-Cookie 도 그대로)
   ▼
[브라우저] authorizationUrl 로 redirect
```

### 4.2 OAuth 콜백 (실제 토큰 발급)
```
[브라우저] POST /api/v1/auth/oauth/github/callback
              Cookie: iam_oauth_state=<state>
              { code, state, redirectUri, rememberMe }
   │
   ▼
[gateway]   publicPath → skip → iam-api 로 forward
   ▼
[iam-api]
   │  ◯ state cookie ↔ body state 비교 (constant-time)
   │  ◯ GitHub /access_token 교환 (code → token)
   │  ◯ GitHub /user, /user/emails 호출
   │  ◯ User / SocialIdentity 매칭 또는 생성
   │  ◯ access JWT 발급 (HS256, sub=userId, iss/aud/exp 클레임)
   │  ◯ refresh token 생성 → DB 에 SHA-256 hash 저장
   │  ◯ Set-Cookie: iam_refresh=<refresh>; HttpOnly; Path=/api/v1/auth
   │  ◯ Body: { accessToken, accessTokenExpiresAt, userId }
   ▼
[gateway] 응답 통과
   ▼
[브라우저] accessToken 저장 (Authorization 헤더로 사용)
```

### 4.3 일반 API 호출 (인증 필요)
```
[브라우저] POST /api/v1/error-cases
              Authorization: Bearer eyJ...
              { title, scope, paste, ... }
   │
   ▼
[gateway:JwtAuthFilter]
   │  ① path 가 publicPath 인가? → /api/v1/error-cases 는 NO
   │  ② Authorization 에서 Bearer 토큰 추출
   │  ③ jwtDecoder.decode(token)
   │     - HS256 서명 검증 (secret 공유)
   │     - exp / nbf / iss / aud / typ=access claim 검증
   │     - 실패 시 → 401 + WWW-Authenticate 헤더, downstream 호출 X
   │  ④ jwt.subject (=userId) 추출
   │  ⑤ HeaderMutatingRequestWrapper 로
   │     - X-User-Id: <subject>
   │     - X-Roles: <roles claim>
   │     - X-Token-Type: access
   │  ⑥ Authorization 헤더는 그대로 둠 (downstream 도 검증 가능하게)
   │
   ▼
[gateway:ProxyController]
   │  routes 에서 가장 긴 prefix 매칭: /api/v1/error-cases → http://localhost:8081
   │  JDK HttpClient 로 forward (모든 헤더 + body)
   ▼
[core-api:ErrorCaseController]
   │  @RequestHeader("X-User-Id") userId: Long  ← gateway 가 보장한 값
   │  @Valid @RequestBody request: CreateErrorCaseRequest
   │  ─ Spring Security 미사용
   │  ─ JWT 디코딩 X
   │  ─ 단순히 헤더 신뢰
   ▼
[core-api:CreateErrorCaseUseCase]
   │  ◯ workspaceQuery.existsWorkspaceForUser(userId, workspaceId)
   │     └─ IamWorkspaceQueryAdapter
   │         └─ iamApiRestClient.get("/api/v1/workspaces/{id}")
   │             └─ interceptor: 현재 요청의 Authorization 을 그대로 forward
   ▼
[iam-api:WorkspaceController]
   │  ◯ @AuthenticationPrincipal Jwt jwt   ← 자체 JWT 검증 (defense-in-depth)
   │  ◯ workspace 멤버십 체크 → 200 또는 403/404
   ▼
[core-api:CreateErrorCaseUseCase] (이어서)
   │  ◯ snapshot 추출 (RegexErrorSnapshotExtractorAdapter)
   │  ◯ fingerprint 산정 (FingerprintGenerator)
   │  ◯ snippet markerId 발급
   │  ◯ attachment markerId 검증 (DB 조회)
   │  ◯ ErrorCase.create(...) → ErrorCaseRepositoryAdapter.save(...)
   │  ◯ 결과 반환
   ▼
[gateway] 응답 통과
   ▼
[브라우저] 201 + { id, fingerprint, ... }
```

### 4.4 토큰 갱신
```
[브라우저] POST /api/v1/auth/refresh
              Cookie: iam_refresh=<refresh-token>
   │
   ▼
[gateway]  /api/v1/auth/refresh 는 publicPath → skip → iam-api forward
   ▼
[iam-api]
   │  ◯ refresh cookie 추출
   │  ◯ DB 의 hash 와 일치 확인 (constant-time)
   │  ◯ 새 access JWT 발급
   │  ◯ refresh 회전: 기존 invalidate + 새로 발급 + Set-Cookie
   │  ◯ Body: { accessToken, accessTokenExpiresAt, userId }
```

### 4.5 로그아웃
```
[브라우저] POST /api/v1/auth/logout
              Cookie: iam_refresh=<refresh-token>
   │
   ▼
[gateway]  publicPath → iam-api forward
   ▼
[iam-api]
   │  ◯ DB 의 refresh token 만료 처리
   │  ◯ Set-Cookie: iam_refresh=; Max-Age=0  (cookie 제거)
   │  ◯ 204 No Content
```

---

## 5. 에러 케이스 생성 — End-to-end 추적

### 5.1 사전: 첨부 업로드
```
POST http://localhost:8000/api/v1/error-attachments
  Authorization: Bearer <jwt>
  Content-Type: multipart/form-data
  ── file=...
  ── title=...
  ── caption=...

  [gateway → core-api]
  [core-api:ErrorCaseAttachmentController]
     ─ @RequestHeader("X-User-Id") userId
     ─ CreateAttachmentUseCase.invoke()
        ─ MarkerIdGeneratorPort.generateAttachmentMarkerId() → "abc12345"
        ─ AttachmentStoragePort.store() → LocalFS 저장 → URL
        ─ ErrorCaseAttachmentRepositoryPort.save() → DB row, errorCaseId=null
     ─ 응답: { markerId: "abc12345", embedToken: "@attach(abc12345)", storageUrl, ... }
```

### 5.2 본 케이스 생성
```
POST http://localhost:8000/api/v1/error-cases
  Authorization: Bearer <jwt>
  Content-Type: application/json
  {
    "title": "[prod] 주문서 API 타임아웃",
    "scope": "core-api / order",
    "paste": "java.net.SocketTimeoutException: ...\n\tat com.foo...",
    "description": "# 문제\n@snippet(de4f5678) 트랜잭션 너무 길어\n@attach(abc12345) 응답 캡쳐",
    "snippets": [
      { "title": "OrderService", "language": "java",
        "filePathOrClass": "com.foo.OrderService",
        "lineRange": "L120-L186", "caption": "...", "code": "..." }
    ],
    "attachmentMarkerIds": ["abc12345"],
    "workspaceId": 1,
    "severity": 2,
    "environment": "k8s / cloud-sql / ap-northeast-2",
    "occurredAt": "2026-05-07T18:10:00"
  }

  ↓

  [gateway:JwtAuthFilter]   JWT 검증 → X-User-Id: 42 주입
  [gateway:ProxyController] /api/v1/error-cases prefix 매칭 → core-api:8081

  ↓

  [core-api:ErrorCaseController.create]
     ─ X-User-Id 헤더 → userId=42
     ─ DTO 검증 (title NotBlank, scope NotBlank, paste NotBlank)
     ─ CreateErrorCaseUseCase.invoke(command)

  ↓

  [core-api:CreateErrorCaseUseCase]
     1. workspaceQuery.existsWorkspaceForUser(42, 1)
        → IamWorkspaceQueryAdapter.existsWorkspaceForUser
          → iamApiRestClient.get("/api/v1/workspaces/1")
            → interceptor: Authorization, X-User-Id forward
          → iam-api 가 200 응답 → true
     2. attachmentRepository.findAllByMarkerIds(["abc12345"])
        → AttachmentRepositoryAdapter
          → AttachmentJpaRepository.findAllByMarkerIdIn
        → 결과 1건 매칭 (마커 검증 OK)
     3. snippets.map { generateMarkerId → CodeSnippet 도메인 객체 }
     4. errorSnapshotExtractor.extract(paste)
        → RegexErrorSnapshotExtractorAdapter
        → ExtractedSnapshot(exceptionClass="java.net.SocketTimeoutException",
                            exceptionMessage="...", rawStackTrace="...")
     5. FingerprintGenerator.generate(exClass, stackTrace)
        → SHA-256("java.net.SocketTimeoutException | <normalized stack>")
     6. ErrorCase.create(ownerUserId=42, ...) → 도메인 객체
     7. errorCaseRepository.save(errorCase)
        → ErrorCaseRepositoryAdapter
          → ErrorCaseJpaRepository.save → INSERT error_case
          → CodeSnippetJpaRepository.saveAll → INSERT error_case_snippet
          → 각 attachment 의 errorCaseId 갱신 → UPDATE error_case_attachment

  ↓

  [응답] 201 Created
  {
    "id": 17,
    "title": "...",
    "status": "OPEN",
    "fingerprint": "9af3...",
    "snippetMarkerIds": ["de4f5678"],
    "attachmentMarkerIds": ["abc12345"],
    "createdAt": "2026-05-07T18:11:23"
  }
```

---

## 6. 책임/관심사 매트릭스

| 관심사 | gateway | iam-api | core-api |
|--------|---------|---------|----------|
| HTTP 진입점 | ⭐ 단독 | ❌ 직접 노출 X | ❌ 직접 노출 X |
| JWT 검증 | ⭐ 1차 | 🔄 자체 (defense-in-depth) | ❌ 모름 |
| JWT 발급 | ❌ | ⭐ 단독 | ❌ |
| User 도메인 | ❌ | ⭐ 단독 | ❌ |
| Workspace 도메인 | ❌ | ⭐ 단독 | 🔄 조회만 |
| ErrorCase 도메인 | ❌ | ❌ | ⭐ 단독 |
| Attachment 저장 | ❌ | ❌ | ⭐ 단독 |
| 인가 (워크스페이스 권한) | ❌ | ⭐ 권한 판정 | 🔄 호출자 |
| Rate-limit / WAF | (미구현, 향후) | ❌ | ❌ |
| Observability (logging/tracing) | 모두 | 모두 | 모두 |

⭐ = 단일 책임자, 🔄 = 보조 역할, ❌ = 책임 없음.

---

## 7. 보안 모델

### 7.1 외부 → 내부
- **반드시 gateway 통과**. 운영에서는 8080/8081 포트는 VPC 외부에서 접근 불가하도록 SG/firewall 설정.
- gateway 가 죽으면 외부 트래픽 100% 차단 → SPOF. 향후 다중 인스턴스 + LB.

### 7.2 내부 → 내부
- core-api → iam-api 호출은 **token relay** (사용자 자격 forward).
- 현재 별도 서비스 인증(M2M) 없음. 같은 사용자 컨텍스트로만 호출 가능.
- 백그라운드 잡(사용자 없는 작업)은 아직 미지원.

### 7.3 토큰 모델
- **Access JWT**: HS256, 15min TTL, sub/iss/aud/typ=access claim
- **Refresh token**: 랜덤 32바이트, DB 에 SHA-256 hash 저장, HttpOnly cookie
- **OAuth state**: 5min TTL, HttpOnly cookie + body 비교
- secret 공유: gateway + iam-api 가 같은 `iam.jwt.secret` 사용 (HS256 의 한계)

### 7.4 알려진 위협 / 완화책
| 위협 | 현재 완화 | 향후 |
|------|----------|------|
| JWT 위조 | secret + HS256 검증 | RS256 + JWKS (secret 분배 제거) |
| Gateway 우회 (직접 8081) | iam-api 자체 검증, core-api 는 X | core-api 도 secret 보유 또는 mTLS |
| Token 탈취 (XSS) | refresh 는 HttpOnly cookie | access 도 cookie 화 검토 |
| Replay | exp claim 짧게 (15min) | jti + 블랙리스트 |
| 인가 우회 | 워크스페이스 권한 iam-api 위임 | core-api 자체 캐시 + invalidation |

---

## 8. 운영 체크리스트

### 로컬 부팅 순서
1. `cd iam-api && docker compose up -d && ./gradlew bootRun`   → 8080
2. `cd core-api && docker compose up -d && ./gradlew bootRun`  → 8081
3. `cd gateway && ./gradlew bootRun`                            → 8000
4. 모든 호출은 `http://localhost:8000` 으로

### 헬스체크
```bash
curl http://localhost:8000/actuator/health   # gateway
curl http://localhost:8000/actuator/health   # → publicPath skip 후 어디로? 현재는 라우트 없음
# 향후: /actuator/health 를 gateway 자체 응답으로 처리하거나 명시적 라우트 추가
```

### 트래픽 흐름 디버깅
- gateway 로그: `[gw] POST /api/v1/error-cases -> http://localhost:8081/api/v1/error-cases`
- core-api 로그: kotlin-logging DEBUG (`org.studieojavry: DEBUG` 로컬)
- 실패 지점 식별:
  - 401 + `WWW-Authenticate: Bearer error="invalid_token"` → gateway 검증 단계
  - 5xx with 빈 body → gateway 의 ProxyController (downstream 연결 실패 가능)
  - core-api 콘솔에 stacktrace → 도메인/JPA 단계

### 문제 시나리오 매핑
| 증상 | 가장 흔한 원인 |
|------|---------------|
| 401 모든 API | gateway 의 secret/issuer/audience 가 iam-api 와 불일치 |
| 401 일부 API | publicPaths 에 누락 (예: /api/v1/auth/refresh) |
| 502/503 | downstream 서비스 다운 또는 baseUrl 오타 |
| 핸들러까지 도달, X-User-Id 없음 | gateway 우회됨 (외부에서 8081 직접 호출) |
| iam-api 호출 실패 | RestClient interceptor 가 헤더 forward 실패, 또는 iam-api workspace endpoint 응답 형식 변경 |

---

## 9. 향후 개선 (Phase 별)

### Phase 1 — 안정화 (현재 + 1-2개월)
- [ ] gateway 의 routes / publicPaths 를 dev/stg/prod profile yml 로 확장
- [ ] gateway HA (2+ 인스턴스 + LB)
- [ ] `/actuator/health` 명시적 라우트
- [ ] 통합 테스트 (Testcontainers + WireMock 으로 iam-api stub)

### Phase 2 — 보안 강화 (2-4개월)
- [ ] **RS256 + JWKS endpoint** — iam-api 가 비밀키로 발급, gateway/iam-api 가 공개키로 검증. secret 공유 제거.
- [ ] M2M 토큰 도입 — 백그라운드 작업/서비스 인증
- [ ] mTLS (gateway ↔ 내부 서비스) — 우회 방지
- [ ] Rate-limit / WAF (gateway 단)

### Phase 3 — 확장 (4개월+)
- [ ] 메시지 큐 도입 (Outbox 패턴 — InvitationCreated, ErrorCaseCreated 이벤트)
- [ ] 검색 인덱서 (insight-api 가 fingerprint/scope 기반 유사 케이스 검색)
- [ ] notification (noti-api 가 이메일/슬랙 발송)
- [ ] OpenAPI 스펙 + 게이트웨이에서 문서 통합

---

## 10. 한 줄 요약

> **gateway 는 인증의 단일 관문**, **iam-api 는 신원/자격증명 도메인 + 자체 검증(방어적)**, **core-api 는 에러 케이스 도메인만 다루며 인증 모름**. 통신은 동기 HTTP + token relay. 책임 분리는 깔끔하지만 secret 공유와 단일 region SPOF 가 다음 단계 개선 포인트.
