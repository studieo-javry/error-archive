# gateway 서비스 구현 문서

> error-archive 모노레포의 **API Gateway / Edge Server**. 외부 트래픽의 진입점이자 인증 검증의 단독 책임자. 본 문서는 게이트웨이 서비스가 **무엇을 책임지고**, **어떻게 구현되어 있고**, **어떤 흐름으로 동작하는지**를 코드 위치까지 포함해서 정리한다.

작성 시점: 2026-05-08 (Spring Cloud Gateway Server MVC 이전 직후)

---

## 0. TL;DR

| | 값 |
|--|-----|
| 위치 | `error-archive/gateway/` (composite-build sibling) |
| 포트 | 8000 (외부 진입) |
| 베이스 | Spring Boot 4.0.4 + Kotlin 2.3.20 + JVM 25 |
| 라우팅 | **Spring Cloud Gateway Server MVC** (servlet 기반 SCG, Spring Cloud `2025.1.1`) |
| 인증 | Spring Security `oauth2ResourceServer.jwt` (HS256) |
| 회복성 | Resilience4j (CircuitBreaker + TimeLimiter + Retry) |
| Kotlin 코드량 | ~150줄 (이전 plain MVC 의 절반) |

---

## 1. 게이트웨이의 책임

```
[외부]                                          [내부 private network]
                ┌──────────────┐                 ┌──────────────┐
[Browser] ──▶   │   gateway    │  ────────────▶  │   iam-api    │
                │   (8000)     │                 │   (8080)     │
                               │  ────────────▶  │   core-api   │
                               │                 │   (8081)     │
                               └─────────────────┘              └──────────────┘
```

### 게이트웨이가 하는 것
- 외부 단일 진입점 제공
- JWT 검증 (HS256, iss/aud/exp/nbf claim)
- 검증 실패 시 401 즉시 반환 — downstream 호출 차단
- 검증 성공 시 SecurityContext 의 Jwt → `X-User-Id` · `X-Roles` · `X-Token-Type` 헤더 주입
- prefix 매칭 라우팅 (`/api/v1/auth/**` → iam-api, `/api/v1/error-cases/**` → core-api 등)
- CORS preflight 처리
- CircuitBreaker / Retry / TimeLimiter (downstream 장애 격리)
- Fallback 응답 (`/__fallback/{service}`)

### 게이트웨이가 하지 않는 것
- ❌ 비즈니스 로직 (도메인 모델 없음)
- ❌ 토큰 발급 (iam-api 단독)
- ❌ 인가/권한 판정 (downstream 책임 — `core-api` 가 ownerId 등 검증)
- ❌ DB 접근 (자체 영속 X)
- ❌ 사용자 정보 영속 (iam-api 가 Identity 관리)

---

## 2. 기술 스택

| 영역 | 선택 | 이유 |
|------|-----|------|
| Web | Spring WebMVC (servlet) | 다른 서비스(iam-api/core-api)와 동일 stack — 디버깅·운영 노하우 통일 |
| Gateway | **Spring Cloud Gateway Server MVC** | reactive 패러다임 전환 부담 회피. 서블릿 기반 SCG |
| Spring Cloud | `2025.1.1` | Spring Boot 4.0 매칭 release (참고: 2025.0.x 는 Boot 3.5 용) |
| 인증 | Spring Security `oauth2ResourceServer.jwt` | OOTB JWT 검증, BearerTokenAuthenticationFilter 활용 |
| JWT 라이브러리 | NimbusJwtDecoder | iam-api 와 동일 라이브러리 — 검증/발급 호환성 보장 |
| 회복성 | Resilience4j | SCG 의 표준 통합. CircuitBreaker + TimeLimiter + Retry |
| 로깅 | kotlin-logging-jvm | KLogger (lazy evaluation) |
| 추적 | micrometer-tracing-bridge-brave | downstream 분산 추적 |

### 왜 Spring Cloud Gateway 였나? — 직접 구현 대비
처음엔 plain MVC + JDK HttpClient 로 시작했다가 (~320줄), CircuitBreaker / Retry / RateLimit 같은 횡단 관심사 직접 구현 부담이 커서 SCG MVC 로 이전. 결과: **Kotlin 코드 ~150줄로 감소** + OOTB resilience 풀세트 획득.

### 왜 reactive(WebFlux) SCG 가 아니었나?
- 다른 서비스가 모두 servlet 기반 — stack 일관성
- Reactor 학습 비용 + 디버깅 지옥 회피
- 우리 트래픽 규모(B2B 도구) 에선 servlet 으로도 충분

---

## 3. 모듈 / 디렉터리 구조

```
gateway/
├── build.gradle.kts                    Spring Cloud BOM + SCG MVC + Resilience4j
├── settings.gradle.kts                 rootProject.name = "gateway"
├── gradle/wrapper/
├── gradlew, gradlew.bat
├── docs/
│   └── gateway-implementation.md       (본 문서)
└── src/main/
    ├── kotlin/org/studieojavry/gateway/
    │   ├── GatewayApplication.kt       @SpringBootApplication + ConfigurationPropertiesScan
    │   ├── config/
    │   │   ├── JwtProperties.kt        iam.jwt.* 바인딩 (secret/issuer/audience)
    │   │   ├── JwtDecoderConfig.kt     NimbusJwtDecoder + iss/aud/typ 검증
    │   │   ├── SecurityConfig.kt       SecurityFilterChain (oauth2ResourceServer)
    │   │   ├── CorsConfig.kt           CorsConfigurationSource + CorsProperties
    │   │   └── ResilienceConfig.kt     CircuitBreaker + TimeLimiter 기본 정책
    │   └── filter/
    │       ├── HeaderInjectionFilter.kt        SecurityContext → 헤더 주입
    │       ├── HeaderMutatingRequestWrapper.kt 서블릿 헤더 추가 wrapper
    │       └── FallbackController.kt           /__fallback/{service} 503 응답
    └── resources/
        ├── application.yml             공통 설정 (이름, profile default)
        └── application-local.yml       routes / 필터 / cors / iam.jwt secret
```

코드량은 8개 Kotlin 파일 + 2개 yml 로 매우 가볍다.

---

## 4. 파일별 구현 상세

### 4.1 `build.gradle.kts` — 의존성 핵심

```kotlin
extra["springCloudVersion"] = "2025.1.1"  // Boot 4.0 매칭

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
  }
}

dependencies {
  // 코어
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-actuator")

  // 인증
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  // 게이트웨이 + 회복성
  implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")
  implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")

  // 관측
  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
}
```

**중요한 함정**: Spring Cloud 버전을 잘못 잡으면 `NoClassDefFoundError: org/springframework/boot/http/client/HttpRedirects` 같은 패키지 변경 이슈로 부팅 실패. Boot 4 ↔ Spring Cloud 매트릭스를 https://spring.io/projects/spring-cloud 에서 항상 확인.

### 4.2 `JwtProperties.kt` — yml 바인딩

```kotlin
@ConfigurationProperties(prefix = "iam.jwt")
data class JwtProperties(
    val secret: String,    // ≥64 char (HS256 ≥256 bits 엔트로피)
    val issuer: String,
    val audience: String
)
```

iam-api 와 **동일한 prefix(`iam.jwt`) + 동일한 secret/issuer/audience 값**을 공유해야 검증이 성공한다. local 프로필에선 평문 placeholder, dev/stg/prod 는 env 주입 (`${IAM_JWT_SECRET}` 등) 권장.

### 4.3 `JwtDecoderConfig.kt` — 검증 정책

```kotlin
@Bean
fun jwtDecoder(jwtProperties: JwtProperties): JwtDecoder {
    val secretKey = SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    val decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build()

    decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
        JwtValidators.createDefault(),                                          // exp / nbf
        JwtClaimValidator("iss") { it == jwtProperties.issuer },                // 발급자
        JwtClaimValidator<List<String>>("aud") { jwtProperties.audience in (it ?: emptyList()) },  // 수신자
        JwtClaimValidator("typ") { it == "access" }                             // access token 만 허용
    ))
    return decoder
}
```

검증되는 것:
1. **서명 (HS256)** — secret 으로 HMAC 검증
2. **만료 (exp)** + **활성 시점 (nbf)** — `JwtValidators.createDefault()`
3. **발급자 (iss)** — iam-api 가 발급한 게 맞는지
4. **수신자 (aud)** — 우리 클라이언트 그룹용인지
5. **토큰 타입 (typ)** — refresh 토큰이 access 자리에 들어오는 걸 막음

이 5가지 중 하나라도 실패하면 `JwtException` → 401.

### 4.4 `SecurityConfig.kt` — 인증의 진짜 시작점

```kotlin
@Bean
fun securityFilterChain(
    http: HttpSecurity,
    jwtDecoder: JwtDecoder,
    headerInjectionFilter: HeaderInjectionFilter
): SecurityFilterChain {
    http
        .csrf { it.disable() }
        .cors(Customizer.withDefaults())                            // ① CorsConfigurationSource Bean 사용
        .sessionManagement { it.sessionCreationPolicy(STATELESS) }
        .authorizeHttpRequests { auth ->
            auth.requestMatchers(
                "/api/v1/auth/oauth/**",                            // ② OAuth 시작/콜백
                "/api/v1/auth/refresh",                             //    refresh cookie 만으로 호출
                "/api/v1/auth/logout",
                "/actuator/health",
                "/__fallback/**"                                    //    CircuitBreaker fallback
            ).permitAll()
            .anyRequest().authenticated()                           // ③ 그 외 = JWT 필수
        }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(UNAUTHORIZED)) }
        .oauth2ResourceServer { it.jwt { j -> j.decoder(jwtDecoder) } }
        .addFilterAfter(headerInjectionFilter, BearerTokenAuthenticationFilter::class.java)
    return http.build()
}
```

핵심 포인트:
- **`permitAll` 경로** 는 검증 skip — 로그인 자체나 토큰 갱신이 차단되면 안 됨
- **`oauth2ResourceServer.jwt`** = 표준 BearerTokenAuthenticationFilter 가 자동 등록됨
- **`addFilterAfter`** = JWT 검증 직후, 우리 HeaderInjectionFilter 가 실행되도록 명시
- **STATELESS** 세션 정책 — 세션 쿠키 등 상태 X

### 4.5 `HeaderInjectionFilter.kt` — Security → downstream 브리지

```kotlin
@Component
class HeaderInjectionFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req, res, chain) {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal

        if (principal is Jwt) {
            val wrapped = HeaderMutatingRequestWrapper(req).apply {
                principal.subject?.let { putHeader("X-User-Id", it) }
                putHeader("X-Roles", principal.getClaimAsStringList("roles").orEmpty().joinToString(","))
                principal.getClaimAsString("typ")?.let { putHeader("X-Token-Type", it) }
            }
            chain.doFilter(wrapped, res)
        } else {
            chain.doFilter(req, res)        // permitAll 경로 — 그대로 통과
        }
    }
}
```

**왜 필요한가**: Spring Security 는 SecurityContext 에 Jwt 만 채워줄 뿐, 그걸 downstream 으로 보낼 헤더로 변환하지 않는다. SCG 의 `AddRequestHeader=X-User-Id,#{authentication.name}` 같은 SpEL 도 가능하지만, 명시적인 Kotlin 코드가 더 디버깅·테스트 친화적.

### 4.6 `HeaderMutatingRequestWrapper.kt` — 서블릿 헤더 추가의 표준

```kotlin
class HeaderMutatingRequestWrapper(req: HttpServletRequest) : HttpServletRequestWrapper(req) {
    private val customHeaders = mutableMapOf<String, String>()

    fun putHeader(name: String, value: String) { customHeaders[name.lowercase()] = value }

    override fun getHeader(name: String) = customHeaders[name.lowercase()] ?: super.getHeader(name)
    override fun getHeaders(name: String) = customHeaders[name.lowercase()]?.let { Collections.enumeration(listOf(it)) } ?: super.getHeaders(name)
    override fun getHeaderNames() = Collections.enumeration(super.getHeaderNames().toList() + customHeaders.keys)
}
```

서블릿 표준에서 `HttpServletRequest` 의 헤더는 불변. 추가하려면 wrapper 가 필수. `getHeader` / `getHeaders` / `getHeaderNames` 3개 모두 오버라이드 안 하면 SCG 의 헤더 forwarding 에서 누락된다.

### 4.7 `CorsConfig.kt` — CORS 정식 활성화

```kotlin
@ConfigurationProperties(prefix = "gateway.cors")
data class CorsProperties(
    val allowedOrigins: List<String>,
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf("*"),
    val allowCredentials: Boolean = true,
    val maxAgeSeconds: Long = 3600
)

@Bean
fun corsConfigurationSource(p: CorsProperties): CorsConfigurationSource =
    UrlBasedCorsConfigurationSource().apply {
        registerCorsConfiguration("/**", CorsConfiguration().apply { /* p 의 값들 적용 */ })
    }
```

local 의 `gateway.cors.allowed-origins` 에 `http://localhost:5173`, `http://localhost:3000` 등 프론트 개발 origin 등록.

**보안 주의**: 운영에선 절대 `*` 와 `allowCredentials=true` 를 동시에 쓰면 안 됨 (Spring 이 거절). 정확한 origin 명시 필수.

### 4.8 `ResilienceConfig.kt` — CircuitBreaker 기본 정책

```kotlin
@Bean
fun defaultCircuitBreakerCustomizer(): Customizer<Resilience4JCircuitBreakerFactory> = Customizer { factory ->
    factory.configureDefault { id ->
        Resilience4JConfigBuilder(id)
            .circuitBreakerConfig(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)                      // 최근 20건 평가
                .failureRateThreshold(50f)                  // 50% 실패 시 OPEN
                .waitDurationInOpenState(Duration.ofSeconds(30))   // 30초 후 HALF_OPEN
                .permittedNumberOfCallsInHalfOpenState(5)   // half-open 에서 5건 시도
                .minimumNumberOfCalls(10)                   // 최소 10건 모이기 전엔 판단 보류
                .build())
            .timeLimiterConfig(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))    // 모든 호출 10초 timeout
                .build())
            .build()
    }
}
```

### 4.9 `FallbackController.kt` — CircuitBreaker OPEN 시 응답

```kotlin
@RestController
@RequestMapping("/__fallback")
class FallbackController {
    @RequestMapping(path = ["/{service}"], method = [GET, POST, PUT, PATCH, DELETE])
    fun fallback(@PathVariable service: String) = ResponseEntity
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(mapOf(
            "error" to "service_unavailable",
            "service" to service,
            "message" to "downstream service is temporarily unavailable, please retry later",
            "timestamp" to Instant.now().toString()
        ))
}
```

`/__fallback/**` 는 SecurityConfig 의 permitAll 에 등록 — 회로 차단 응답이 401 되는 어처구니없는 상황 방지.

### 4.10 `application-local.yml` — 라우트와 필터의 진짜 정의

```yaml
spring:
  cloud:
    gateway:
      mvc:
        routes:
          - id: iam-api
            uri: http://localhost:8080
            predicates:
              - Path=/api/v1/auth/**,/api/v1/users/**,/api/v1/workspaces/**,/api/v1/invitations/**
            filters:
              - name: CircuitBreaker
                args:
                  name: iamApiCB
                  fallbackPath: /__fallback/iam-api
              - name: Retry
                args:
                  retries: 2
                  series: SERVER_ERROR
                  methods: GET,POST
                  backoff:
                    firstBackoff: 50ms
                    maxBackoff: 500ms
                    factor: 2

          - id: core-api
            uri: http://localhost:8081
            predicates:
              - Path=/api/v1/error-cases/**,/api/v1/error-attachments/**
            filters:
              - name: CircuitBreaker
                args: { name: coreApiCB, fallbackPath: /__fallback/core-api }
              - name: Retry
                args: { retries: 2, series: SERVER_ERROR, methods: GET,POST,
                        backoff: { firstBackoff: 50ms, maxBackoff: 500ms, factor: 2 } }
```

라우트 추가는 코드 수정 없이 **yml 만 변경**하면 됨. 운영에서 dev/stg/prod 별로 다른 baseUrl 만 갈아끼우면 끝.

---

## 5. 요청 처리 흐름 (Filter Chain Order)

```
들어온 요청
   │
   ▼ ① Tomcat 디스패치
   │
   ▼ ② Spring Security FilterChainProxy (-100 priority)
   │      ├─ CorsFilter             — preflight 처리
   │      ├─ BearerTokenAuthenticationFilter
   │      │     ├─ Authorization 헤더에서 토큰 추출
   │      │     ├─ jwtDecoder.decode(token)
   │      │     │     ├─ HS256 서명 검증
   │      │     │     ├─ exp / nbf / iss / aud / typ 검증
   │      │     │     └─ 실패 시 → 다음 필터로 (인증 실패)
   │      │     └─ 성공 시 SecurityContextHolder 에 Authentication(principal=Jwt) 저장
   │      ├─ HeaderInjectionFilter (★ addFilterAfter 로 끼움)
   │      │     ├─ SecurityContext.authentication.principal == Jwt ?
   │      │     ├─ X-User-Id / X-Roles / X-Token-Type 헤더를 wrapper 에 추가
   │      │     └─ 다음 필터에 wrapped request 전달
   │      ├─ AuthorizationFilter
   │      │     ├─ permitAll 경로 ? → 통과
   │      │     ├─ authenticated() 인데 principal 없음 ? → ExceptionTranslationFilter 가 401
   │      │     └─ 통과 시 다음으로
   │      └─ ExceptionTranslationFilter
   │            └─ AuthenticationException → HttpStatusEntryPoint → 401 반환
   │
   ▼ ③ Spring MVC 디스패처
   │      ├─ /__fallback/{service} → FallbackController
   │      └─ 그 외 모든 경로 → SCG Server MVC 핸들러
   │
   ▼ ④ SCG Server MVC 핸들러
   │      ├─ routes 의 predicates 매칭 (Path=/api/v1/...)
   │      ├─ filters 적용 (CircuitBreaker → Retry → 실제 호출)
   │      │     ├─ CircuitBreaker.OPEN ? → fallbackPath 로 forward → /__fallback/...
   │      │     ├─ Retry: SERVER_ERROR 5xx 시 backoff 재시도
   │      │     └─ TimeLimiter: 10초 초과 시 timeout
   │      ├─ uri 의 baseUrl + path 로 downstream 호출
   │      └─ 응답을 그대로 클라이언트에 전달
   │
   ▼ 응답
```

요점:
- ②번에서 인증 실패 시 ③④번까지 도달하지 않음 — downstream 호출 자체가 차단
- HeaderInjectionFilter 위치(② 안쪽)가 중요 — Security 가 SecurityContext 채운 직후
- ④번의 Retry / CircuitBreaker 는 5xx 와 timeout 만 다룸 (4xx 는 통과 — 클라이언트 잘못이지 downstream 잘못이 아님)

---

## 6. 보안 모델 상세

### 6.1 secret 공유 모델 (HS256)
```
iam-api    : iam.jwt.secret 으로 access token 서명 (발급)
gateway    : iam.jwt.secret 으로 검증
```
같은 secret 을 두 서비스가 공유. local 에서는 평문 placeholder, 운영에서는 env / secret manager 주입 필수.

**미래 개선 (RS256 + JWKS)**:
- iam-api 가 비밀키로 서명, 공개키를 `/.well-known/jwks.json` 으로 노출
- gateway 가 JWKS endpoint 에서 공개키 가져와 검증
- **secret 공유 자체가 사라짐** → 더 깔끔한 분리

### 6.2 검증의 Defense-in-Depth
현재 모델에서 **iam-api 도 자체 JWT 검증을 유지**한다. 이유:
- gateway 우회 공격(직접 8080 호출) 차단
- VPC 격리 무너졌을 때 마지막 방어선
- 검증 비용 거의 0 (HS256 ~ 마이크로초)

이 결정은 운영 환경의 신뢰도에 따라 조정 가능. mTLS / VPC isolation 이 단단해지면 iam-api 의 자체 검증을 빼고 X-User-Id 만 신뢰해도 됨.

### 6.3 토큰 외 자격증명
- Refresh token 은 게이트웨이가 **검증하지 않음** (`/api/v1/auth/refresh` 는 permitAll). iam-api 가 DB hash 비교로 검증
- OAuth state cookie 는 게이트웨이가 **그대로 통과** (`Set-Cookie` 응답 헤더 forwarding 됨)

### 6.4 헤더 신뢰 모델
downstream 서비스(특히 core-api) 는 게이트웨이가 주입한 `X-User-Id` 를 **무조건 신뢰**한다. 이게 안전한 이유:
- 외부에서는 게이트웨이만 노출 (운영에서 8081 은 VPC 외부 접근 차단)
- 외부 클라이언트가 직접 X-User-Id 헤더를 보내도, 게이트웨이가 그 헤더를 그대로 받지 않음 (HeaderInjectionFilter 가 SecurityContext 기준으로 **덮어씀**)

> 단, **외부 헤더를 게이트웨이가 strip 하는 로직이 명시적으로 있는지** 한 번 더 검증 필요. 현재 HeaderMutatingRequestWrapper 의 `putHeader` 는 lowercase 매칭으로 동일 키를 덮어쓰므로 안전. 하지만 변종 케이스(`X-User-ID` 대문자 등)가 있을 수 있음 — 향후 ProblemDetail 응답 도입과 함께 explicit strip filter 추가 권장.

---

## 7. 회복성 정책

### 7.1 CircuitBreaker 상태 머신
```
        (실패율 < 50%)
       ┌─────────────────┐
       │                 │
       ▼                 │
   ┌────────┐  실패율    ┌──────┐
   │ CLOSED │──50%↑─▶   │ OPEN │
   └────────┘            └──┬───┘
       ▲                    │ 30초 대기
       │ 성공률 OK          ▼
       │                ┌─────────┐
       │ ←──────────── │ HALF_OPEN│
       │   5건 시도      │         │
       │                └─────────┘
       │                    │
       │ 실패율 50%↑       │
       └────────────────────┘
            (다시 OPEN)
```

`coreApiCB`, `iamApiCB` 두 개의 독립 회로를 운영. **한 서비스 장애가 다른 서비스에 영향 안 미침**.

### 7.2 Retry 정책
```yaml
retries: 2
series: SERVER_ERROR             # 5xx 만 (4xx 는 재시도 X)
methods: GET, POST               # 멱등성 위험한 PUT/PATCH/DELETE 는 재시도 X (지금은 명시)
backoff:
  firstBackoff: 50ms
  maxBackoff: 500ms
  factor: 2                      # 50ms → 100ms → 200ms → 400ms → 500ms (cap)
```

**중요한 한계**: POST 도 재시도하면 **중복 생성** 위험. 운영에서는 Idempotency-Key 헤더 + 서버측 중복 방지 로직 도입 권장.

### 7.3 TimeLimiter
모든 downstream 호출 10초 timeout. 게이트웨이 thread pool 이 느린 downstream 에 잡히는 걸 방지.

---

## 8. CORS

### 8.1 왜 정식 활성화가 필요한가
프론트가 `localhost:5173` 에서 게이트웨이 `localhost:8000` 으로 호출할 때:
1. 브라우저가 preflight `OPTIONS` 요청 보냄
2. 게이트웨이가 `Access-Control-Allow-Origin / -Methods / -Headers` 응답해야 함
3. 그래야 본 요청 진행

CORS 가 disable 이거나 origin 매칭 실패 시 → 브라우저 콘솔에 CORS 에러 + 실제 요청 안 감.

### 8.2 운영 권장
```yaml
gateway:
  cors:
    allowed-origins:
      - https://app.studieo-javry.com
      - https://app.dev.studieo-javry.com
    allow-credentials: true
```
- `allowedOrigins` 에 와일드카드 X — 정확한 origin 명시
- `allowCredentials: true` 면 쿠키 / Authorization 가 cross-origin 으로 흐를 수 있게 됨

---

## 9. 운영 / 모니터링

### 9.1 노출되는 actuator endpoint
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, gateway, metrics
```

| Endpoint | 용도 |
|---------|------|
| `/actuator/health` | k8s liveness / readiness |
| `/actuator/info` | 빌드 정보 |
| `/actuator/gateway/routes` | 등록된 라우트 조회 (운영 디버깅) |
| `/actuator/metrics` | Prometheus 메트릭 |

### 9.2 핵심 Resilience4j 메트릭 (Micrometer 자동 노출)
```
resilience4j.circuitbreaker.calls{name="iamApiCB", kind="successful"}
resilience4j.circuitbreaker.calls{name="iamApiCB", kind="failed"}
resilience4j.circuitbreaker.state{name="iamApiCB", state="open"}
resilience4j.timelimiter.calls{name="iamApiCB", kind="timeout"}
resilience4j.retry.calls{name="...", kind="successful_with_retry"}
```

Prometheus / Grafana 에 바로 연동.

### 9.3 로그 패턴
local 프로필:
```
logging:
  level:
    org.studieojavry.gateway: DEBUG
    org.springframework.cloud.gateway: INFO
```

prod 에서는 prod yml 의 분산 추적 패턴 (`%X{traceId:-},%X{spanId:-}`) 활용. iam-api 와 동일 패턴.

---

## 10. 빌드 / 실행

### 10.1 의존성 검증
```bash
cd gateway
./gradlew compileKotlin       # Kotlin 컴파일 확인
./gradlew assemble            # bootJar 빌드 (테스트 제외)
./gradlew bootRun --args='--spring.profiles.active=local'   # 로컬 부팅
```

### 10.2 동작 검증 (현재 통과)
```
✅ /actuator/health → 200
✅ POST /api/v1/error-cases (no token) → 401 (JWT 필수)
✅ CORS 헤더 정상 (Vary: Origin)
✅ Spring Boot 4.0.4 + Spring Cloud 2025.1.1 호환 부팅
```

### 10.3 모노레포 통합 빌드
루트 `settings.gradle.kts` 에 `includeBuild("gateway")` 등록되어 있어 다음 한 줄로 모든 서비스 빌드 가능:
```bash
./gradlew buildAll       # gateway / core-api / iam-api / publish-api / insight-api / noti-api
```

---

## 11. 알려진 함정 / 주의사항

### 11.1 Spring Cloud 버전 매트릭스
| Spring Boot | Spring Cloud |
|-------------|--------------|
| 3.4.x | 2024.0.x |
| 3.5.x | 2025.0.x |
| **4.0.x** | **2025.1.x** ← 우리 |

잘못된 매칭 시 `NoClassDefFoundError` 부팅 실패. 새 서비스 추가 시 동일 버전 사용.

### 11.2 yml 의 routes vs reactive SCG yml
- reactive SCG: `spring.cloud.gateway.routes` (mvc 미포함)
- Server MVC: **`spring.cloud.gateway.mvc.routes`** ← 우리

같은 SCG 라도 yml 키 경로가 다르므로 Stack Overflow / 블로그 답변 인용 시 **server-mvc 전용인지 확인**.

### 11.3 Retry 의 함정 — POST 재시도
현재 yml 에 `methods: GET, POST` 로 설정되어 있어 POST 도 재시도. 비-멱등 API (예: 결제) 에서는 위험. 향후 작업:
- Idempotency-Key 헤더 도입 (클라이언트가 UUID 생성, 서버가 캐시로 중복 제거)
- 또는 Retry methods 를 `GET` 만으로 제한

### 11.4 secret 공유의 한계
gateway + iam-api 가 같은 secret 을 보유. iam-api 가 침해되면 gateway 검증도 무력화. **장기적으로 RS256 + JWKS 권장**.

### 11.5 외부 헤더 strip 미구현
HeaderInjectionFilter 가 X-User-Id 를 덮어쓰긴 하지만, 외부에서 직접 보낸 X-User-Id 를 명시적으로 제거하는 로직은 없음. lowercase 매칭으로 사실상 덮어쓰기는 되지만, **explicit strip filter 추가** 가 안전한 패턴.

---

## 12. 향후 개선 로드맵

### Phase 1 — 안정화 (현재 + 1개월)
- [ ] dev/stg/prod profile yml 의 routes / cors / circuit breaker 정책 분기
- [ ] ProblemDetail 표준 401 / 503 응답 본문
- [ ] 외부 X-User-Id 헤더 explicit strip filter
- [ ] gateway HA (2+ 인스턴스 + LB)
- [ ] Idempotency-Key 도입 (POST retry 안전)

### Phase 2 — 보안 강화 (2-4개월)
- [ ] **RS256 + JWKS** — secret 공유 제거
- [ ] mTLS (gateway ↔ 내부 서비스)
- [ ] Rate Limit (Redis 기반, IP/user 별)
- [ ] WAF / Bot detection (Enterprise gateway 또는 Cloudflare 단)

### Phase 3 — 확장 (4개월+)
- [ ] 분산 추적 강화 (Zipkin/Tempo 통합)
- [ ] OpenAPI 스펙 통합 (gateway 가 모든 서비스의 OpenAPI 를 모아 단일 docs 노출)
- [ ] WebSocket / SSE 라우팅 (필요 시)
- [ ] 트래픽 큰 서비스 도입 시 → Kong / Envoy 마이그레이션 검토

---

## 13. 핵심 코드 레퍼런스

| 책임 | 코드 위치 |
|------|----------|
| JWT 검증 정책 | `gateway/src/main/kotlin/.../config/JwtDecoderConfig.kt` |
| 인증 / 라우트 보호 | `gateway/src/main/kotlin/.../config/SecurityConfig.kt` |
| SecurityContext → 헤더 | `gateway/src/main/kotlin/.../filter/HeaderInjectionFilter.kt` |
| CORS | `gateway/src/main/kotlin/.../config/CorsConfig.kt` |
| CircuitBreaker / TimeLimiter 기본값 | `gateway/src/main/kotlin/.../config/ResilienceConfig.kt` |
| Fallback 응답 | `gateway/src/main/kotlin/.../filter/FallbackController.kt` |
| 라우트 + 필터 정의 | `gateway/src/main/resources/application-local.yml` |
| 의존성 (Spring Cloud BOM) | `gateway/build.gradle.kts` |

---

## 14. 한 줄 요약

> **gateway 는 외부 진입점 + 인증 단독 책임자**. Spring Boot 4 + Spring Cloud Gateway Server MVC + Resilience4j 조합으로 ~150줄 Kotlin 으로 구성. 직접 구현했던 proxy/필터를 표준 도구로 대체해 **CircuitBreaker · Retry · CORS · TimeLimiter 가 OOTB**. 향후 RS256/JWKS · Rate Limit · k8s 이전 등 점진적 강화.
