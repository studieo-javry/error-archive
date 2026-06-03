# 인증/인가 아키텍처 — 사용자 · 게이트웨이 · 내부 서비스

> 최종 갱신: 2026-05-20
> 관련 변경: `docs/changelog.md` 의 "내부 서비스 간 인증 — 단명 Internal JWT 도입" 항목
> 이 문서는 **현재 동작하는 방식**, **그렇게 만든 이유**, **임시 결정과 그 근거**, **메쉬 도입 시 어디만 바꾸면 되는지** 를 한 곳에 정리한다.

---

## 0. TL;DR — 한눈에 보기

인증을 **세 개의 경계**로 나눠서 본다.

| # | 경계 | 무엇을 증명하나 | 수단 (현재) | 수단 (메쉬 도입 후) |
|---|------|----------------|-------------|---------------------|
| 1 | 사용자 ↔ 게이트웨이 | "이 요청을 보낸 사람은 로그인한 사용자 X 다" | **사용자 JWT** (Bearer, HMAC) | 변화 없음 |
| 2 | 게이트웨이 → 내부 서비스 | "이 요청은 게이트웨이가 보냈고, 사용자 X 를 위한 것이다" | **Internal JWT** (`X-Internal-Auth`, RS256) | mTLS(누가) + Internal JWT(누구를 위해) |
| 3 | 내부 서비스 ↔ 내부 서비스 | "이 호출은 core-api 가 사용자 X 를 위해 iam-api 에 보냈다" | **Internal JWT** (`iss=core-api`) | mTLS(누가) + Internal JWT(누구를 위해) |

핵심 원칙: **"누가 호출했는가(워크로드 신원)"** 와 **"누구를 위해 호출했는가(사용자 컨텍스트)"** 는 별개의 질문이다.
- 지금은 **둘 다 Internal JWT** 가 답한다 (`iss`/서명 = 누가, `sub`/`roles` = 누구를 위해).
- 메쉬 도입 후엔 **"누가" 는 mTLS** 가, **"누구를 위해" 는 Internal JWT** 가 답하도록 책임이 갈라진다.
- 그래서 지금 코드는 **검증 로직의 절반(서명·iss·aud)만 메쉬로 들어내면 되도록** 설계되어 있다.

```
[Browser/SPA]
    │  ① 사용자 JWT (Authorization: Bearer …, HMAC 서명)
    ▼
┌─────────────┐  Spring Security oauth2ResourceServer 가 사용자 JWT 검증
│  gateway    │  HeaderInjectionFilter 가 사용자 신원 → Internal JWT 발급(RS256)
│  :8000      │
└─────────────┘
    │  ② X-Internal-Auth: <Internal JWT> (iss=gateway, aud=core-api 또는 iam-api)
    ├──────────────────────────────┐
    ▼                              ▼
┌─────────────┐              ┌─────────────┐
│  core-api   │              │  iam-api    │
│  :8081      │              │  :8080      │
└─────────────┘              └─────────────┘
    │  ③ X-Internal-Auth: <Internal JWT> (iss=core-api, aud=iam-api)
    └──────────────────────────────▶ iam-api
```

---

## 1. 경계 1 — 사용자 ↔ 게이트웨이 (변경 없음)

### 흐름
1. 사용자가 GitHub OAuth 등으로 로그인하면 **iam-api 가 사용자 JWT 를 발급**한다 (`NimbusJwtIssuerAdapter`, HMAC `HS256`, `iam.jwt.secret`).
2. 이후 모든 요청은 `Authorization: Bearer <사용자 JWT>` 로 **게이트웨이(:8000)** 에 들어온다.
3. 게이트웨이의 Spring Security(`gateway/.../config/SecurityConfig.kt`)가 `oauth2ResourceServer` 로 이 JWT 를 검증한다.
   - `permitAll`: `/api/v1/auth/oauth/**`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/actuator/health`, `/__fallback/**` → 검증 skip
   - 그 외 모든 경로: JWT 검증 실패 시 **게이트웨이에서 401** (downstream 도달 전 차단)

### 원리
- 게이트웨이는 인증의 **단일 진입점**이다. 사용자 JWT 검증은 여기서 한 번만 한다.
- 사용자 JWT 는 **게이트웨이 밖으로 나가지 않는다** (아래 "왜 token relay 를 버렸나" 참고).

> 이 경계는 메쉬 도입 후에도 그대로다. 외부 클라이언트는 브라우저(REST)이므로 mTLS 대상이 아니다.

---

## 2. 경계 2 — 게이트웨이 → 내부 서비스

### 흐름
1. 게이트웨이가 사용자 JWT 검증을 끝내면 `HeaderInjectionFilter`(`gateway/.../filter/HeaderInjectionFilter.kt`)가 실행된다.
2. 요청 경로로 **대상 서비스(`aud`)** 를 결정한다:
   - `/api/v1/error-cases/**`, `/api/v1/error-attachments/**` → `aud=core-api`
   - 그 외(`/api/v1/auth|users|workspaces|invitations/**`) → `aud=iam-api`
3. 게이트웨이가 **자기 RSA 개인키로 서명한 Internal JWT** 를 발급해 `X-Internal-Auth` 헤더로 주입한다.
4. downstream(core-api/iam-api)이 게이트웨이 공개키로 서명·`aud`·`exp` 를 검증하고 사용자 신원을 신뢰한다.

### Internal JWT 구조 (claims)
```json
{
  "iss": "gateway",      // 호출자 — 메쉬 도입 후 이 검증은 mTLS 로 대체
  "aud": "core-api",     // 대상 — 다른 서비스용 토큰 재사용(confused deputy) 차단
  "sub": "12345",        // 원 사용자 ID
  "roles": ["USER"],     // 사용자 권한
  "iat": …, "exp": …,    // 단명 (기본 60초)
  "jti": "…"             // 재생 추적용 nonce
}
```

### 수신 측 검증 (서비스마다 구현이 다름 — 의도된 차이)
- **core-api**: 공유 모듈의 `InternalTokenAuthenticationFilter` 를 보안 체인에 끼운다. 검증 성공 시 `InternalAuthentication`(principal = `userId: Long`)을 SecurityContext 에 채운다. 컨트롤러는 `@AuthenticationPrincipal userId: Long` 으로 받는다.
- **iam-api**: 컨트롤러 ~10개가 모두 `@AuthenticationPrincipal Jwt` 로 `jwt.subject` 를 읽기 때문에, principal 타입을 `Jwt` 로 **유지**한다. 그래서 공유 필터 대신 **커스텀 `BearerTokenResolver`(X-Internal-Auth 헤더에서 토큰 추출) + `MultiIssuerJwtDecoder`(iss 별 공개키 라우팅)** 를 `oauth2ResourceServer` 에 결합한다. → 컨트롤러 변경 0.

> **왜 두 서비스가 검증 방식이 다른가?** core-api 는 인증 계층이 아예 없던 상태라 깔끔하게 `Long` principal 로 시작했다. iam-api 는 이미 `Jwt` principal 에 의존하는 컨트롤러가 많아, 그걸 건드리지 않는 게 변경 위험이 훨씬 작았다. 둘 다 **같은 Internal JWT 를 검증**하므로 토큰 포맷은 동일하고, principal 표현만 다르다.

---

## 3. 경계 3 — 내부 서비스 ↔ 내부 서비스 (core-api → iam-api)

### 흐름
1. core-api 가 워크스페이스 조회 등으로 iam-api 를 호출한다 (`IamWorkspaceQueryAdapter` → `iamApiRestClient`).
2. `IamApiRestClientConfig` 의 interceptor 가 **현재 SecurityContext 의 사용자**(게이트웨이 토큰을 검증해 담긴 값)를 읽는다.
3. core-api 가 **자기 RSA 개인키로** 새 Internal JWT 를 발급한다:
   ```
   iss = core-api      ← iam-api 는 이걸로 "core-api 를 거쳐 들어온 호출" 임을 확인
   aud = iam-api
   sub = 원 사용자 ID
   roles = 사용자 권한
   ```
4. iam-api 의 `MultiIssuerJwtDecoder` 가 `iss=core-api` 를 보고 core-api 공개키로 검증한다.

### 원리 — 왜 새 토큰을 발급하나 (token relay 와의 차이)
- **과거(token relay)**: core-api 가 사용자의 원본 `Authorization`(사용자 JWT)을 그대로 iam-api 로 전달했다. → iam-api 입장에선 "이 호출이 core-api 를 거쳤는지" 알 수 없고, 사용자 JWT 가 여러 서비스를 떠도는 **confused deputy** 위험이 있었다.
- **현재**: core-api 가 **자기 신원으로** 토큰을 새로 만든다. iam-api 가 받은 토큰의 `iss=core-api` 가 곧 호출 경로의 암호학적 증거다. 사용자 JWT 는 게이트웨이 밖으로 나가지 않는다.

---

## 4. 시나리오별 시퀀스 (한눈에)

### 시나리오 A — 사용자가 에러케이스 생성 (`POST /api/v1/error-cases`)
```
Browser → gateway        : Authorization: Bearer <user JWT>
gateway                  : user JWT 검증 → aud=core-api Internal JWT 발급
gateway → core-api       : X-Internal-Auth: <iss=gateway, aud=core-api, sub=123>
core-api                 : InternalTokenAuthenticationFilter 검증 → principal=123(Long)
core-api 컨트롤러         : @AuthenticationPrincipal userId=123 으로 처리
```

### 시나리오 B — core-api 가 워크스페이스 존재 확인 (core-api → iam-api)
```
core-api (위 A 흐름 도중) : SecurityContext 에 사용자 123 존재
core-api → iam-api       : X-Internal-Auth: <iss=core-api, aud=iam-api, sub=123>
iam-api                  : MultiIssuerJwtDecoder(iss=core-api 공개키)로 검증 → principal=Jwt(sub=123)
iam-api 컨트롤러          : @AuthenticationPrincipal Jwt 로 jwt.subject=123 처리
```

### 시나리오 C — 사용자가 자기 프로필 조회 (`GET /api/v1/users/me`)
```
Browser → gateway        : Authorization: Bearer <user JWT>
gateway                  : user JWT 검증 → aud=iam-api Internal JWT 발급
gateway → iam-api        : X-Internal-Auth: <iss=gateway, aud=iam-api, sub=123>
iam-api                  : MultiIssuerJwtDecoder(iss=gateway 공개키)로 검증 → principal=Jwt(sub=123)
```

### 시나리오 D — 로그인/토큰 갱신 (`permitAll`)
```
Browser → gateway        : (인증 없음 또는 refresh 쿠키)
gateway                  : permitAll → SecurityContext 에 Jwt 없음 → Internal JWT 미발급
gateway → iam-api        : X-Internal-Auth 없음
iam-api                  : permitAll 경로 → 통과 (쿠키/OAuth 로 자체 처리)
```

---

## 5. 키 관리 현황 (임시)

| 서비스 | 역할 | 가진 키 |
|--------|------|---------|
| gateway | 발급자 | 자기 RSA **개인키** (`internal-auth.issuer.private-key-pem`) |
| core-api | 발급자 + 검증자 | 자기 RSA **개인키**(iam-api 호출용) + gateway **공개키**(게이트웨이 토큰 검증용) |
| iam-api | 검증자 | gateway **공개키** + core-api **공개키** (`internal-auth.verifier.known-issuers`) |

- **알고리즘**: RS256 (비대칭). 메쉬의 SPIFFE JWT-SVID 와 검증 인터페이스가 같아 마이그레이션이 매끄럽다.
- **현재 키 배포 방식**: `application-local.yml` 에 PEM 인라인 (로컬 dev 전용 테스트 키, 진짜 비밀 아님).
- **운영**: `internal-auth.*.private-key-pem` / `known-issuers.*` 를 ENV/secret manager 로 주입해야 한다 (아직 미구현 — 10절 후속 과제).

---

## 6. 왜 지금 이렇게 했는가 (설계 결정과 근거)

| 결정 | 이유 |
|------|------|
| **단명(60s) Internal JWT** | 토큰이 캐싱·유출돼도 노출 윈도우 최소화. clock skew 30초 허용. |
| **`aud` 바인딩 필수** | core-api 용 토큰을 iam-api 가 받으면 거부 → token confusion / confused deputy 차단. |
| **비대칭 RS256 + JWKS 류 검증** | 메쉬(SPIFFE JWT-SVID)와 검증 코드가 동일해 이관 비용 최소. Spring Security 와 자연스럽게 결합. |
| **token relay 제거** | 사용자 JWT 가 내부망을 떠돌지 않게 함. 호출 경로(`iss`)가 토큰에 명시되어 추적 가능. |
| **공유 모듈(`shared-internal-auth`)** | 발급/검증 로직 중복 제거. 한 곳만 고치면 전 서비스 반영. |
| **`includeBuild`(composite) 유지** | 기존 "각 서비스 독립 빌드" 철학 유지. 멀티모듈 `include` 로 강결합하지 않음. 소비 측은 GAV 좌표로 의존. |
| **iam-api 는 Jwt principal 유지** | 컨트롤러 ~10개가 `@AuthenticationPrincipal Jwt` 의존. 변경 위험 대비 이득이 없어 principal 타입을 보존(커스텀 resolver/decoder). |
| **core-api 에 Spring Security 신규 도입** | 기존엔 평문 `X-User-Id` 헤더를 무검증 신뢰 → 8081 에 직접 도달 가능한 누구나 위조 가능했음. 이게 이번 작업의 핵심 보안 동기. |

---

## 7. 임시(잠정) 결정과 그 이유 — "나중에 바꿀 것들"

이 섹션이 가장 중요하다. **무엇이 임시이고, 왜 임시이며, 메쉬 도입 시 어디만 바꾸면 되는지**.

| 임시 사항 | 왜 지금 이렇게 | 메쉬 도입 후 목표 | 바꿀 위치 |
|-----------|----------------|-------------------|-----------|
| **앱이 직접 서명/iss/aud 검증** | 메쉬가 없으니 워크로드 신원을 앱이 증명할 수밖에 없음 | mTLS + SPIFFE 가 "누가" 를 보장 → 앱은 `sub`/`roles`(누구를 위해)만 추출 | `InternalTokenAuthenticationFilter`(core-api), `MultiIssuerJwtDecoder`(iam-api) 에서 서명/iss 검증 제거. `aud` 는 defense-in-depth 로 유지 가능 |
| **PEM 키 properties 인라인** | 서비스 3개뿐, JWKS 인프라 미구축 | SPIFFE 가 단명 cert/JWT-SVID 자동 발급·회전 (또는 JWKS endpoint) | `application-*.yml` 의 `internal-auth.*-key-pem` 제거, 키 소스를 메쉬/JWKS 로 교체. `PemKeyParser` 는 그때 폐기 대상 |
| **gateway 가 경로로 `aud` 결정** | SCG 라우팅과 정합하는 단순 매핑 | 동일 (메쉬 AuthorizationPolicy 가 추가 강제) | `HeaderInjectionFilter.resolveAudience()` |
| **dev/stg/prod 키 미설정** | 로컬 baseline 우선 | ENV/secret 주입 | `application-{dev,stg,prod}.yml` 에 `internal-auth` 블록 추가 |
| **gateway 가 원본 Authorization 을 계속 forward 가능성** | 무해해서 미정리 | 명시적 스트립 | gateway 라우팅 필터에 헤더 제거 추가 |
| **iam-api 와 core-api 의 검증 방식 차이** | iam-api 컨트롤러 보존 위해 | 메쉬가 신원 보장하면 둘 다 평문 헤더(`X-User-Id`)로 회귀해도 안전 → 통일 가능 | iam-api 컨트롤러를 `@AuthenticationPrincipal Long` 으로 옮기면 공유 필터로 통일 가능(선택) |

### 메쉬 도입 시 검증 로직이 어떻게 슬림해지나
```diff
[수신 측 검증]
- 서명 검증 (iss 공개키)        ← mTLS 가 호출자를 보장하므로 제거 가능
- iss 검증                      ← SPIFFE ID 로 대체
- aud 검증                      ← 유지 가능 (앱 레벨 방어선)
- exp 검증                      ← 유지 (또는 메쉬 단명 cert)
  sub/roles 추출               ← 그대로 — 메쉬는 "사용자" 개념이 없으므로 앱에 남음
```
즉 **공유 모듈을 통째로 들어내는 게 아니라, 모듈 안의 서명/iss 검증만 비우는** 마이그레이션이다.

---

## 8. 메쉬 도입 후 목표 아키텍처 (확정: A안)

> **결정**: 메쉬+mTLS 도입 후 내부 hop 은 **A안(평문 헤더 + mTLS)** 으로 간다. B안(서명 토큰 유지)은 아래 "확장 가능 포인트"로 별도 보관.

### 전제 — 두 질문은 다른 주체가 답한다
- **"누가 호출했나"(워크로드 신원)** → **mTLS/SPIFFE**(메쉬 사이드카가 처리). 메쉬는 `user_id` 개념이 없다.
- **"누구를 위해 호출했나"(사용자 컨텍스트)** → **앱 레이어**. 메쉬가 대신 못 한다.
- ⚠️ 흔한 오해: "메쉬가 internal JWT 를 검증한다"가 아니다. mTLS 는 *호출자(서비스)* 를 검증할 뿐, 사용자 토큰 검증과는 다른 질문이다. (Envoy `jwt_authn`/Istio `RequestAuthentication` 으로 JWT 검증을 사이드카에 오프로드할 수는 있으나, 그건 보통 **엣지의 엔드유저 토큰**용이고 내부 hop 에선 mTLS 와 중복이다.)

### A안 목표 그림
```
[엣지]  gateway : 엔드유저 JWT 검증 유지 (필요 시 Envoy 로 오프로드 가능)
[내부]  hop 마다 : mTLS = 누가(워크로드 신원)
                  + AuthorizationPolicy = "gateway SA만 core-api 호출, core-api SA만 iam-api 호출" 강제
                  + 평문 헤더(X-User-Id / X-Roles) = 누구를 위해(사용자 컨텍스트)
```

### 왜 A안인가
- 메쉬가 mTLS+AuthorizationPolicy 로 "이 호출은 진짜 gateway/core-api 발" 을 보장하는 순간, internal JWT 의 "누가" 검증(서명+`iss`)은 **중복**이 된다.
- 사용자 컨텍스트(`sub`/`roles`)는 채널이 이미 인증·인가됐으므로 평문 헤더로 실어도 안전하다.
- **키 발급/회전 운영 부담이 사라진다** — 현재 가장 큰 임시 비용이 없어진다.
- 현재 코드를 "검증 로직을 0으로 슬림화 가능"하게 설계한 이유가 이 경로다 (7절 diff 참고).

### A안으로 갈 때 실제로 바꾸는 것
1. `HeaderInjectionFilter`(gateway): internal JWT 발급 → 평문 `X-User-Id`/`X-Roles` 주입으로 회귀.
2. `InternalTokenAuthenticationFilter`(core-api): 토큰 검증 제거 → 평문 헤더에서 `userId` 추출만.
3. `MultiIssuerJwtDecoder`/resolver(iam-api): 제거 → 평문 헤더 기반 principal 구성. (이때 iam-api 컨트롤러를 `Long` principal 로 통일하면 core-api 와 검증 방식이 일치.)
4. `IamApiRestClientConfig`(core-api): 토큰 발급 제거 → 평문 헤더 forward.
5. `internal-auth.*` 키 설정 및 `PemKeyParser` 폐기.
6. 메쉬 측: `PeerAuthentication`(mTLS STRICT) + hop 별 `AuthorizationPolicy` 작성.

→ 호출 흐름·컨트롤러 시그니처는 그대로, **`shared-internal-auth` 의 검증/발급 부분만 비운다.**

### 확장 가능 포인트 — B안 (서명 토큰 유지, defense in depth)

다음 상황이면 A안 위에 B안을 **추가 레이어**로 얹는다. 지금 당장은 안 가지만, 가능성으로 기록해 둔다.

- **언제 고려하나**: "메쉬 설정 실수 / 사이드카 우회 / 파드 네트워크 내부 침투"까지 위협 모델에 넣는 고보안 요구가 생길 때. 평문 헤더는 메쉬가 뚫리면 다시 위조 가능하지만, 서명 토큰은 그 경우에도 위조 불가.
- **무엇을 유지하나**: 사용자 컨텍스트를 평문이 아니라 **서명된 토큰**으로 전파. mTLS(누가) 위에 서명 토큰(누구를 위해, 위조 불가)을 겹친다.
- **권장 구현**: 지금처럼 "앱이 RS256 키를 직접 들고 발급"하지 말고 **SPIFFE JWT-SVID** 사용 — 메쉬가 발급·자동 회전하는 비대칭 JWT. 키 운영을 메쉬가 가져가므로 현재 임시 비용(PEM 인라인/회전 부재)이 사라진다.
- **현재 코드와의 관계**: 지금 만든 internal JWT 구조가 사실상 B안의 "수동 버전"이다. B안으로 가려면 **검증은 유지하되 키 소스만 properties→SPIFFE 로 교체**하면 된다 (`shared-internal-auth` 의 키 로딩 부분). 발급/검증 로직과 클레임(`sub`/`roles`/`aud`)은 재사용.
- **트레이드오프**: defense in depth 확보 ↔ 사이드카 JWT 검증/발급 설정 + 토큰 클레임 관리라는 운영 복잡도 추가.

> 요약: 현재 internal JWT 구조는 **A안으로도 B안으로도 갈 수 있는 중간 지점**이다. 기본은 A(검증 비우고 평문+mTLS), 고보안 요구가 생기면 B(키 소스만 SPIFFE 로 교체). 둘 다 호출 흐름·컨트롤러는 그대로다.

---

## 9. 컴포넌트 / 파일 맵 (어디를 보면 되나)

| 관심사 | 파일 |
|--------|------|
| Internal JWT 발급 | `shared-internal-auth/.../InternalTokenIssuer.kt` |
| Internal JWT 검증 필터 (core-api 용) | `shared-internal-auth/.../InternalTokenAuthenticationFilter.kt` |
| 검증 후 principal 표현 (Long) | `shared-internal-auth/.../InternalAuthentication.kt` |
| 설정 스키마 (`internal-auth.*`) | `shared-internal-auth/.../InternalAuthProperties.kt` |
| PEM 파싱 | `shared-internal-auth/.../PemKeyParser.kt` |
| 조건부 빈 등록 + 필터 자동등록 차단 | `shared-internal-auth/.../InternalAuthAutoConfiguration.kt` |
| 게이트웨이 토큰 발급(경로→aud) | `gateway/.../filter/HeaderInjectionFilter.kt` |
| 게이트웨이 발급자 키 | `gateway/.../resources/application-local.yml` (`internal-auth.issuer`) |
| core-api 보안 체인 | `core-api/.../shared/config/SecurityConfig.kt` |
| core-api→iam-api 토큰 발급 | `core-api/.../errorcase/infrastructure/iam/IamApiRestClientConfig.kt` |
| core-api 키(검증+발급) | `core-api/.../resources/application-local.yml` (`internal-auth.verifier`/`issuer`) |
| iam-api 토큰 추출 + 디코더 | `iam-api/.../auth/infrastructure/security/InternalTokenResourceServerConfig.kt` |
| iam-api 보안 체인 결합 | `iam-api/.../auth/infrastructure/security/SecurityConfig.kt` |
| iam-api 키(검증) | `iam-api/.../resources/application-local.yml` + `src/test/resources/application-test.yml` (`internal-auth.verifier`) |
| composite 빌드 등록 | 루트 `settings.gradle.kts`, `build.gradle.kts` |

---

## 10. 알려진 제약 / 후속 과제

1. **dev/stg/prod 프로파일 미구성**: `internal-auth` 키가 없어 해당 프로파일로 기동하면 issuer/verifier 빈 부재로 실패. ENV 주입 보강 필요.
2. ~~**cross-service 컨텍스트 전파 검증 필요**~~ → **해결됨 (2026-05-20)**: core-api 의 CircuitBreaker 가 TimeLimiter 때문에 supplier 를 별도 ExecutorService 스레드에서 실행 → `SecurityContextHolder`(thread-local)가 전파되지 않아 `IamApiRestClientConfig` 의 `currentUser()` 가 null → 토큰 미부착 → iam-api 401. `ResilienceConfig` 에서 CircuitBreaker executor 를 `DelegatingSecurityContextExecutorService` 로 감싸 제출 스레드의 SecurityContext 를 worker 로 전파하도록 수정. 추가로 `/error` 내부 디스패치가 보안 체인에 막혀 컨트롤러의 4xx/5xx 가 401 로 둔갑하던 문제를 `/error` `permitAll` 로 해결. (changelog 2026-05-20 21:09 참고)
3. **원본 Authorization 헤더 스트립 미적용**: 현재 무해하나 명시적 제거 권장.
4. **키 회전 절차 부재**: `keyId`(kid) 는 토큰 헤더에 들어가지만, 회전 운영 절차는 메쉬/JWKS 전환 시 정립.

---

## 11. 용어

- **워크로드 신원(workload identity)**: "어느 서비스/프로세스가 호출했는가". 현재 Internal JWT 의 `iss`+서명, 미래 mTLS+SPIFFE.
- **사용자 컨텍스트(user context)**: "어느 엔드유저를 위한 호출인가". 현재·미래 모두 Internal JWT 의 `sub`/`roles`.
- **token relay**: 원 사용자 토큰(JWT)을 downstream 으로 그대로 전달하는 패턴. 이번에 제거함.
- **confused deputy**: 권한 있는 중개자가 자신의 권한으로 의도치 않은 요청을 대신 수행하게 되는 취약점. `aud` 바인딩 + 서비스별 토큰 발급으로 차단.
- **composite build**: Gradle `includeBuild` — 독립 빌드들을 조합. 멀티모듈(`include`)과 다름.
