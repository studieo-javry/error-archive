# 쿠키 & CORS 실무 가이드

> iam-api 의 OAuth state 쿠키, refresh token 쿠키를 다루다가 실제로 막히는 지점들을 정리한 문서. 스펙 사전이 아니라 "왜 안 되는지" 를 빠르게 진단하기 위한 체크리스트로 쓴다.

---

## 1. 가장 먼저 알아야 할 한 가지 — 쿠키는 클라이언트별로 분리된다

쿠키는 **요청을 보낸 그 클라이언트의 jar 에만 저장**된다. "같은 컴퓨터에서 같은 브라우저를 쓰니까 공유되겠지" 는 틀린 직관이다. 실제로는 다음이 모두 별개의 jar:

| 클라이언트                       | jar 위치                                       |
|----------------------------------|------------------------------------------------|
| Chrome 일반 창                   | Chrome 프로파일별 cookie store                 |
| Chrome 시크릿 창                 | 세션 종료 시 사라지는 별도 jar                 |
| Chrome 다른 프로파일             | 또 별도 jar                                    |
| Safari / Firefox                 | 각자 별도 jar (브라우저끼리 공유 X)            |
| Postman 데스크탑                 | Postman 자체 cookie jar                        |
| Postman 웹                       | 브라우저에 종속 (Postman Agent 필요)           |
| curl                             | 명시적으로 `-b/-c` 안 쓰면 매 호출마다 새 jar |
| 백엔드 서버끼리 호출 (RestClient)| HTTP 클라이언트 인스턴스별                     |

### 실무에서 자주 빠지는 함정

**"Postman 으로 authorize 호출 → Chrome 에서 GitHub 승인" 패턴**:
- `Set-Cookie: iam_oauth_state=...` 는 **Postman jar 에만** 저장됨
- Chrome 은 그 쿠키를 본 적이 없으므로 DevTools 에 절대 안 나타남
- callback 호출도 Postman 에서 해야 그 쿠키가 같이 실려감
- → Chrome 에서 쿠키를 보고 싶다면 **authorize 호출도 Chrome 에서** 해야 한다

---

## 2. Origin / Domain / Path / Scheme — 쿠키가 실리는지 결정하는 4가지 축

### 2.1 Origin = scheme + host + port

브라우저 보안 정책(SOP, CORS)은 **origin 단위** 로 결정한다. 다음은 모두 다른 origin:

```
http://localhost:8080      ≠   https://localhost:8080
http://localhost:8080      ≠   http://localhost:5173
http://localhost           ≠   http://127.0.0.1
http://localhost           ≠   http://localhost.localdomain
```

특히 `localhost` ↔ `127.0.0.1` 은 사람 눈엔 같아 보여도 브라우저에겐 다른 host 다. 한쪽에 쿠키가 박히면 다른 쪽엔 안 실린다.

### 2.2 Cookie Domain — 쿠키가 어느 호스트로 보내질지 결정

`Set-Cookie: ...; Domain=...` 로 결정. iam-api 의 `AuthCookieFactory` 는 `AuthCookieProperties.domain` 을 그대로 사용:

| profile  | `iam.cookie.domain`        | 쿠키 전송 대상                                   |
|----------|----------------------------|--------------------------------------------------|
| local    | (미지정)                   | **정확히 그 호스트**(`localhost`)에만           |
| dev      | `.dev.studieo-javry.com`   | `*.dev.studieo-javry.com` 모든 서브도메인       |
| stg      | `.stg.studieo-javry.com`   | `*.stg.studieo-javry.com`                       |
| prod     | `.studieo-javry.com`       | `*.studieo-javry.com`                            |

**중요한 규칙들:**
- Domain 미지정 시: "정확히 그 호스트" 만 (서브도메인 포함 X). 가장 좁은 범위.
- Domain 지정 시: 그 도메인 + **모든 서브도메인**. 앞에 점(`.`) 유무는 현대 브라우저에선 동일하게 처리.
- **다른 도메인** 으로는 절대 못 박는다. `app.dev.studieo-javry.com` 에서 응답하면서 `Domain=other.com` 으로는 set 불가 (브라우저가 거절).
- **public suffix list** 에 있는 TLD 단위는 못 박는다. `Domain=.com`, `Domain=.co.kr` 안 됨.

### 2.3 Path — 쿠키가 어느 경로로 보내질지 결정

iam-api 기준:

| 쿠키                | Path 설정                                       |
|---------------------|--------------------------------------------------|
| `iam_refresh`       | `/api/v1/auth` (`AuthCookieProperties.path`)    |
| `iam_oauth_state`   | `/api/v1/auth/oauth` (`AuthCookieFactory:31`)   |

→ `iam_oauth_state` 는 `/api/v1/auth/oauth/...` 경로 호출에만 자동으로 실리고, 그 외 경로(예: `/api/v1/users/me`)에는 안 실린다. `iam_refresh` 는 `/api/v1/auth/...` 전반.

**Path 매칭은 prefix 매칭**이다. `Path=/api/v1/auth` 면 `/api/v1/authXXX` 에는 안 실리고 `/api/v1/auth/...` 에만 실린다 (정확히는 `/` 경계).

### 2.4 Scheme — `Secure` 속성

`Secure` 속성이 붙은 쿠키는 **HTTPS 요청에만** 실린다.

| profile | `iam.cookie.secure` | 동작                                       |
|---------|---------------------|--------------------------------------------|
| local   | `false`             | HTTP 로컬 개발용. HTTP 에도 실림           |
| dev/stg/prod | `true`         | HTTPS 에서만 실림. HTTP 로 부르면 누락     |

prod 에서 실수로 HTTP 로 호출하면 쿠키가 누락되어 인증이 무한 루프에 빠질 수 있다.

---

## 3. SameSite — Cross-site 쿠키의 핵심

iam-api 는 `iam.cookie.same-site: Lax` (모든 환경 동일).

### 3.1 SameSite 3 가지 값

| 값        | 동작                                                                        |
|-----------|-----------------------------------------------------------------------------|
| `Strict`  | 같은 사이트 navigation 에만 실림. 외부에서 들어오는 모든 요청에 안 실림    |
| `Lax`     | top-level GET navigation (주소창 입력, `<a href>`)에 실림. POST/iframe 안 실림 |
| `None`    | 모든 요청에 실림. 단 **`Secure` 필수** (HTTPS 만)                            |

### 3.2 "Same-site" 의 정의 — origin 과 다르다

`SameSite` 의 "site" 는 **eTLD+1** (effective TLD + 1 label) 을 의미한다.

```
app.studieo-javry.com  ←→  api.studieo-javry.com   : same-site (eTLD+1 = studieo-javry.com)
app.studieo-javry.com  ←→  studieo-javry.com       : same-site
app.studieo-javry.com  ←→  app.studieo-javry.io    : cross-site (eTLD+1 다름)
http://example.com     ←→  https://example.com     : same-site (scheme 무관)
```

→ `app.dev.studieo-javry.com` (프론트) 와 `iam.dev.studieo-javry.com` (백엔드) 는 **same-site** 이므로 `SameSite=Lax` 로도 fetch/XHR 에 쿠키가 실린다 (단, CORS `credentials: 'include'` 필요. 다음 절 참고).

### 3.3 SameSite=Lax 인데 Postman/curl 에선 왜 그냥 되나?

`SameSite` 는 **브라우저가 강제하는 정책**이다. Postman, curl 같은 비-브라우저 클라이언트는 이 검사를 안 해서 쿠키가 그냥 실린다. 그래서:
- **Postman/curl 로 잘 되는데 브라우저에서만 안 됨** → SameSite 의심.
- **브라우저 둘 다 안 됨** → CORS / Domain / Secure / Path 의심.

### 3.4 실무 선택 가이드

- 프론트와 백엔드가 **same-site** (같은 부모 도메인): `Lax` 면 충분. 추천.
- 프론트와 백엔드가 **cross-site** (다른 부모 도메인): `None` + `Secure` 필수. HTTPS 강제 + CORS credentials 설정 필요.
- 로컬 개발: `Lax` + `Secure=false`. iam-api 로컬 프로필이 이 형태.

---

## 4. CORS — 쿠키와 함께 가장 자주 막히는 지점

### 4.1 CORS 가 쿠키와 만나는 지점

브라우저가 cross-origin 요청에 쿠키를 실으려면 **3가지 모두** 만족해야 한다:

1. **클라이언트**: `fetch(url, { credentials: 'include' })` 또는 `xhr.withCredentials = true`
2. **서버 응답 헤더**: `Access-Control-Allow-Credentials: true`
3. **서버 응답 헤더**: `Access-Control-Allow-Origin: <정확한 origin>` (와일드카드 `*` 금지)

하나라도 빠지면 브라우저가 쿠키를 무시한다 (요청은 가도 쿠키만 누락 또는 응답을 JS 가 못 읽음).

### 4.2 Spring Security 의 CORS 설정 위치

`SecurityConfig.kt:24` 에서 `.cors(Customizer.withDefaults())` 를 호출하므로, `CorsConfigurationSource` Bean 이 있어야 동작한다. iam-api 에 별도 Bean 정의가 없다면 **CORS preflight 가 막혀서** cross-origin 호출 자체가 거절될 수 있다 (실제로는 같은 origin 이라면 무관).

local 환경에서 프론트(`localhost:5173`) 가 백엔드(`localhost:8080`)에 호출 → 포트 다름 → cross-origin → CORS 설정 필요.

### 4.3 Preflight (OPTIONS) 요청

브라우저는 다음 조건에서 본 요청 전에 `OPTIONS` 를 먼저 보낸다:
- HTTP 메서드가 GET/HEAD/POST 가 아닐 때
- `Content-Type` 이 `application/json` 일 때 (form-urlencoded, text/plain, multipart 가 아닐 때)
- 커스텀 헤더 (`Authorization`, `X-...`) 가 있을 때

Postman/curl 은 preflight 안 보낸다. → **브라우저에서만 실패하는** 흔한 케이스.

---

## 5. HttpOnly — JS 에서 못 읽는 쿠키

`AuthCookieFactory.kt:42` 에서 `httpOnly(true)` 가 모든 인증 쿠키에 박혀 있다.

### 5.1 HttpOnly 의 동작

- `document.cookie` 로 **읽기/쓰기 불가** (XSS 방어)
- HTTP 요청에는 **자동으로 실림** (브라우저 자체 동작)
- DevTools **Application 패널** 에선 **그대로 보인다** (DevTools 는 JS API 가 아니라 HTTP 헤더 파서)
- Network 탭의 응답 헤더에서 `Set-Cookie` 도 정상적으로 보인다

### 5.2 그래서 실무에서

- **클라이언트 JS 가 토큰을 직접 읽을 일이 없게** 설계해야 한다 (어차피 못 읽으니까).
- 서버가 `Set-Cookie` 로 내려주고, 다음 요청에 자동으로 실리는 흐름만 의존.
- "프론트가 refresh token 을 직접 쥐고 있다가 access token 갱신할 때 보낸다" 같은 설계는 HttpOnly 와 충돌. iam-api 는 cookie 기반 refresh 를 채택했으므로 일관됨.
- 읽고 싶을 땐: **DevTools Application 탭** → Storage → Cookies → 도메인 선택.

---

## 6. 시나리오별 진단 매트릭스

### 시나리오 A: Postman 에선 되는데 브라우저에서 401

| 의심 항목                                         | 확인 방법                                                    |
|--------------------------------------------------|-------------------------------------------------------------|
| SameSite 정책으로 쿠키 누락                       | DevTools Network 탭 → 그 요청 → Cookies 섹션 확인            |
| CORS 막힘                                         | Console 에 CORS 에러 메시지                                   |
| `credentials: 'include'` 안 붙임                  | fetch 옵션 다시 확인                                          |
| `Access-Control-Allow-Credentials: true` 누락     | 응답 헤더 확인                                               |
| Authorization 헤더가 잘못된 토큰으로 자동 동봉     | DevTools Network → Request Headers                          |

### 시나리오 B: 쿠키가 분명히 박혔는데 다음 요청에 안 실림

| 의심 항목                  | 확인 방법                                            |
|---------------------------|-----------------------------------------------------|
| Path 불일치                | 쿠키의 `Path` 속성 vs 요청 경로 prefix              |
| Domain 불일치              | 쿠키 Domain 과 요청 호스트                          |
| `localhost` ↔ `127.0.0.1`  | 쿠키 박힌 호스트와 호출 호스트가 동일한지           |
| Secure 쿠키를 HTTP 로 호출 | 쿠키의 `Secure` 속성 확인                           |
| SameSite=Lax + POST        | top-level navigation 이 아니므로 cross-site 면 누락 |

### 시나리오 C: DevTools 에 쿠키가 안 보임

| 의심 항목                       | 확인 방법                                              |
|---------------------------------|-------------------------------------------------------|
| 다른 클라이언트로 호출함         | Postman 에서 호출했으면 Chrome 엔 당연히 없음          |
| Domain 이 다른 origin 으로 박힘 | DevTools 의 다른 도메인 선택해서 확인                  |
| SameSite=None + Secure 위반     | HTTP 환경에서 SameSite=None 이면 브라우저가 거절       |
| 쿠키가 즉시 만료됨               | `Max-Age=0` 또는 과거 `Expires`                        |

---

## 7. iam-api 쿠키 한눈에 보기

```
┌────────────────────┬──────────────────────────┬─────────────────┬───────────┐
│ 쿠키                │ 용도                      │ Path             │ TTL       │
├────────────────────┼──────────────────────────┼─────────────────┼───────────┤
│ iam_refresh        │ JWT refresh token        │ /api/v1/auth    │ 8h or 30d │
│ iam_oauth_state    │ OAuth CSRF state         │ /api/v1/auth/   │ 5 min     │
│                    │                          │      oauth      │           │
└────────────────────┴──────────────────────────┴─────────────────┴───────────┘

공통 속성 (AuthCookieFactory.baseBuilder)
- HttpOnly: true (하드코딩, 변경 불가)
- Secure: profile 별 (local=false, dev/stg/prod=true)
- SameSite: Lax (모든 profile)
- Domain: profile 별 (local=미지정, dev=*.dev.studieo-javry.com, ...)
```

---

## 8. 실전 디버깅 권장 순서

1. **클라이언트가 쿠키를 받았는가?** → DevTools Network 탭 → 응답의 `Set-Cookie` 헤더
2. **쿠키가 jar 에 저장되었는가?** → DevTools Application 탭 → Cookies → 도메인 선택
3. **저장은 됐는데 다음 요청에 실리는가?** → DevTools Network 탭 → 요청의 Cookies 섹션
4. **서버가 쿠키를 정상 인식했는가?** → 서버 로그 (kotlin-logging DEBUG 켜고 `OAuthController.validateState` 단계 추적)

각 단계가 끝나는 지점을 끊어서 확인하면 어디서 꼬였는지 1분 내로 잡힌다.

---

## 9. 참고 — Postman 으로 쿠키 흐름 검증하기

Postman 데스크탑은 cookie jar 가 자동 동작한다. 단:
- 한 컬렉션 내에서 호출할 때만 jar 가 공유됨 (다른 워크스페이스/사람이면 별개).
- Cookies 패널 (요청창 우측) 에서 도메인별 쿠키 직접 편집 가능.
- Postman 웹은 브라우저 보안 정책에 묶이므로 **Postman Agent** 또는 데스크탑 앱을 사용.

**raw HTTP 헤더를 보고 싶으면** curl 이 가장 빠르다:

```bash
curl -i -c jar.txt -X POST http://localhost:8080/api/v1/auth/oauth/github/authorize \
  -H "Content-Type: application/json" \
  -d '{"redirectUri":"http://localhost:5173/oauth/callback"}'

cat jar.txt   # 저장된 쿠키 확인
```

`cat jar.txt` 하면 `iam_oauth_state	...	v4bK9...` 형태로 나온다.

---

## 10. 한 줄 요약

> 쿠키 디버깅은 **(1) 어느 클라이언트의 jar 인가 (2) Domain/Path/Secure/SameSite 모두 일치하는가 (3) cross-origin 이면 CORS credentials 가 양쪽 다 켜져 있는가** 를 순서대로 확인한다.
