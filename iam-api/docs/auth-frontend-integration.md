# Frontend ↔ iam-api 연동 가이드 (GitHub OAuth)

이 문서는 SPA(React/Vue/Svelte 등 브라우저 기반)와 `iam-api`를 결합해 **회원가입 / 로그인 / 로그아웃 / 자동 토큰 갱신**을 구현할 때의 정확한 호출 흐름과 주의점을 정리한 것이다.

> 모바일(iOS Apple Sign-In) 흐름은 부록 §11 참고.

---

## 0. 사전 셋업 (한 번만)

### 0.1 GitHub OAuth App 등록
1. GitHub → Settings → Developer settings → OAuth Apps → New OAuth App
2. **Authorization callback URL**: 프론트엔드 콜백 페이지의 절대 URL.
   - 예: `https://app.studieo-javry.com/oauth/github/callback`
   - **로컬 개발용**: `http://localhost:5173/oauth/github/callback`
3. 발급된 `Client ID` / `Client Secret`을 백엔드 환경변수로 주입.
   - `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`

### 0.2 도메인 / 쿠키 정책
- **운영**: 프론트와 iam-api가 **같은 부모 도메인** 아래에 있어야 쿠키 전송이 자연스러움.
  - 프론트: `https://app.studieo-javry.com`
  - iam-api: `https://iam.studieo-javry.com`
  - `iam.cookie.domain=".studieo-javry.com"`로 설정 → 두 서브도메인 모두 쿠키 공유.
- **CORS**: iam-api에 프론트 origin을 허용해야 하고, **`allowCredentials=true`** 가 필수(쿠키 전송 때문).
- **로컬 개발**: 프론트 `localhost:5173` ↔ iam-api `localhost:8080` 인 경우, 쿠키는 `Secure=false; SameSite=Lax`로 임시 변경하거나(권장 X), 프론트 dev 서버를 **HTTPS**로 띄우거나, 같은 origin에서 reverse proxy로 묶는 방법을 쓴다.

### 0.3 클라이언트 fetch 공통 설정
모든 인증 관련 요청에 **반드시 쿠키를 포함**해야 한다.

```ts
// fetch
fetch(url, { credentials: "include", ... })

// axios
axios.create({ baseURL: "https://iam.studieo-javry.com", withCredentials: true })
```

---

## 1. 등장 인물과 토큰

| 항목 | 위치 | 수명 | 비고 |
|---|---|---|---|
| Access Token (JWT) | 클라이언트 **메모리** (변수 또는 zustand/redux 상태) | 15분 | localStorage 금지(XSS 위험) |
| Refresh Token | **HttpOnly 쿠키** `iam_refresh` | 14일, 회전 | JS에서 읽기 불가 |
| OAuth State | **HttpOnly 쿠키** `iam_oauth_state` | 5분 | CSRF 방지용, 콜백 후 삭제 |
| `userId` | 응답 본문 / 메모리 | - | UI 표시용 |

> **"Access Token을 어디에 저장하나"** 는 자주 나오는 질문이다. 이 시스템은 **메모리**가 정답이다. 새로고침하면 사라지는데, 그땐 `/api/v1/auth/refresh`로 즉시 복구되므로 UX 문제는 없다.

---

## 2. 핵심 엔드포인트 요약

| Method | Path | 인증 | 본문 | 쿠키 |
|---|---|---|---|---|
| POST | `/api/v1/auth/oauth/github/authorize` | 불필요 | `{ redirectUri }` | ← `iam_oauth_state` 발급 |
| POST | `/api/v1/auth/oauth/github/callback`  | 불필요 | `{ code, state, redirectUri, rememberMe }` | → `iam_oauth_state` 전송 / ← `iam_refresh` 발급 |
| POST | `/api/v1/auth/refresh` | 불필요 | (없음) | → `iam_refresh` 전송 / ← `iam_refresh` 회전 |
| POST | `/api/v1/auth/logout`  | 선택 | (없음) | → `iam_refresh` 전송 / ← `iam_refresh` 만료 |
| GET   | `/api/v1/users/me` | **JWT 필수** | (없음) | — |
| PATCH | `/api/v1/users/me` | **JWT 필수** | `{ displayName?, avatarUrl?, bio?, clearAvatar?, clearBio? }` | — |

`GET/PATCH /api/v1/users/me` 응답 형태:
```json
{
  "userId": 42,
  "email": "octocat@github.com",
  "displayName": "The Octocat",
  "avatarUrl": "https://avatars.githubusercontent.com/u/583231?v=4",
  "bio": "에러 아카이브 유지보수 중",
  "status": "ACTIVE"
}
```

### rememberMe 정책 (callback 본문)

| `rememberMe` | refresh TTL | 쿠키 형태 | 효과 |
|---|---|---|---|
| `true`  | 30일 (기본) | **영속 쿠키** (maxAge 설정) | 브라우저 재시작 후에도 자동 로그인 |
| `false` (기본) | 8시간 (기본) | **세션 쿠키** (maxAge 없음) | 브라우저 종료 시 쿠키 삭제 → 자동 로그인 OFF |

> 회전(rotation) 시에도 같은 정책 유지. 명시적 logout은 family를 revoke + 쿠키 만료 → 다음 `/refresh` 401로 자동 로그인 비활성화됨.

성공 응답 본문 형태(공통):
```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJhbGciOi...",
  "accessTokenExpiresAt": "2026-04-29T15:25:30Z",
  "userId": 42
}
```

---

## 3. 회원가입(=신규 GitHub 계정으로 첫 로그인) 시나리오

> **회원가입과 로그인은 같은 엔드포인트**다. 서버가 `(provider, providerUserId)` 조회로 신규/기존을 자동 판별한다.

### 3.1 단계별 흐름

```
[1] FE: 사용자가 "GitHub으로 시작하기" 버튼 클릭
       ↓
[2] FE → BE: POST /api/v1/auth/oauth/github/authorize
            { "redirectUri": "https://app.../oauth/github/callback" }
       ↓
[3] BE → FE: 200 { authorizationUrl }
            Set-Cookie: iam_oauth_state=...; HttpOnly; Path=/api/v1/auth/oauth
       ↓
[4] FE: window.location.href = authorizationUrl
       ↓ (브라우저가 GitHub으로 이동)
[5] User: GitHub에서 로그인 + 권한 승인 ("Authorize OAuth App")
       ↓
[6] GitHub → 브라우저: 302 redirect to redirectUri?code=...&state=...
       ↓
[7] FE 콜백 페이지: URL에서 code, state 추출
       ↓
[8] FE → BE: POST /api/v1/auth/oauth/github/callback
            { "code", "state", "redirectUri" }
            (브라우저가 자동으로 iam_oauth_state 쿠키 동봉)
       ↓
[9] BE 내부:
    - state 쿠키 vs 본문 state 상수시간 비교 (CSRF 검증)
    - GitHub /login/oauth/access_token 호출 → access token 획득
    - GitHub /user, /user/emails 호출 → providerUserId, email, name 획득
    - SocialIdentity 조회: 없으므로 신규 → User INSERT + SocialIdentity INSERT
    - JWT(15분) + Refresh(14일, family=F1) 발급, refresh는 hash만 DB 저장
       ↓
[10] BE → FE: 200 { tokenType, accessToken, accessTokenExpiresAt, userId }
            Set-Cookie: iam_refresh=...; HttpOnly; Secure; SameSite=Lax
            Set-Cookie: iam_oauth_state=; Max-Age=0  (state 폐기)
       ↓
[11] FE: accessToken을 메모리 저장, userId로 UI 갱신, 메인 화면으로 이동
```

### 3.2 신규 vs 기존 차이는?

```
[9] 단계에서만 다름
  - 신규: User + SocialIdentity 둘 다 INSERT
  - 기존: SocialIdentity 발견 → User 로드 후 ensureSignInAllowed()
```

응답 형태와 클라이언트 흐름은 **완전히 동일**. 프론트는 회원가입/로그인 버튼을 따로 둘 필요가 없다. (UX상 분리하고 싶으면 백엔드가 `isNewUser` 같은 플래그를 응답에 추가하는 옵션도 있지만 현재 미구현.)

### 3.3 코드 예시 (TypeScript)

```ts
// AuthService.ts
export class AuthService {
  private accessToken: string | null = null
  private accessTokenExpiresAt: Date | null = null
  private userId: number | null = null

  async startGithubLogin() {
    const redirectUri = `${window.location.origin}/oauth/github/callback`
    const res = await fetch(`${API_BASE}/api/v1/auth/oauth/github/authorize`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ redirectUri })
    })
    if (!res.ok) throw new Error("authorize failed")
    const { authorizationUrl } = await res.json()
    window.location.href = authorizationUrl
  }

  async finishGithubLogin(code: string, state: string, rememberMe: boolean) {
    const redirectUri = `${window.location.origin}/oauth/github/callback`
    const res = await fetch(`${API_BASE}/api/v1/auth/oauth/github/callback`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, state, redirectUri, rememberMe })
    })
    if (!res.ok) throw new Error("callback failed")
    const data = await res.json()
    this.accessToken = data.accessToken
    this.accessTokenExpiresAt = new Date(data.accessTokenExpiresAt)
    this.userId = data.userId
    return data
  }

  getAccessToken() { return this.accessToken }
  isLoggedIn() { return this.accessToken !== null }
}
```

```tsx
// LoginButton.tsx
function LoginButton() {
  return <button onClick={() => authService.startGithubLogin()}>GitHub으로 시작하기</button>
}

// /oauth/github/callback 라우트 컴포넌트
function GithubCallbackPage() {
  const navigate = useNavigate()
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get("code")
    const state = params.get("state")
    const error = params.get("error")  // 사용자가 GitHub에서 거부한 경우
    if (error) { navigate("/login?error=" + error); return }
    if (!code || !state) { navigate("/login?error=missing_params"); return }
    authService.finishGithubLogin(code, state)
      .then(() => navigate("/"))
      .catch(() => navigate("/login?error=callback_failed"))
  }, [])
  return <div>로그인 처리 중…</div>
}
```

---

## 4. 로그인 시나리오 (기존 사용자)

§3과 **흐름·코드 모두 동일**. 차이는 서버 내부에서 `User`를 새로 만들지 않고 기존 row를 로드한다는 것뿐. 클라이언트는 신경 쓸 필요 없다.

### 4.1 "이미 로그인되어 있는지" 자동 판별

페이지 새로고침 직후 `accessToken`은 메모리에서 사라지지만 `iam_refresh` 쿠키는 살아있다. 앱 부팅 시점에 한 번 `/refresh`를 호출해 본다.

```ts
// App.tsx
useEffect(() => {
  authService.tryRefresh()              // 성공하면 로그인 상태 복원
    .catch(() => { /* 비로그인 */ })
    .finally(() => setBooted(true))
}, [])
```

```ts
async tryRefresh() {
  const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
    method: "POST",
    credentials: "include"
  })
  if (!res.ok) throw new Error("not logged in")
  const data = await res.json()
  this.accessToken = data.accessToken
  this.accessTokenExpiresAt = new Date(data.accessTokenExpiresAt)
  this.userId = data.userId
}
```

---

## 5. 로그아웃 시나리오

```
[1] FE: 사용자 "로그아웃" 클릭
[2] FE → BE: POST /api/v1/auth/logout
            Authorization: Bearer <accessToken>   (선택, 있으면 더 강력)
            (iam_refresh 쿠키 자동 동봉)
[3] BE: 
    - 쿠키의 refresh 토큰 → family 전체 revoke (이 디바이스 세션 종료)
    - JWT가 동봉돼있으면 추가로 revokeAllByUserId(...) 가능 — 현 구현은 둘 다 호출
[4] BE → FE: 204 No Content
            Set-Cookie: iam_refresh=; Max-Age=0
[5] FE: accessToken/userId 메모리 클리어, 로그인 페이지로 이동
```

### 5.1 "이 디바이스만" vs "모든 디바이스" 로그아웃

현재 구현(`LogoutUseCase`)은:
- refresh 쿠키가 있으면 그 **family만** revoke (이 디바이스만)
- userId(JWT)가 같이 있으면 **모든 family** revoke (모든 디바이스)

프론트 UX 옵션:
- **일반 로그아웃 버튼**: Authorization 헤더 **제외**하고 호출 → 이 디바이스만.
- **"모든 기기에서 로그아웃" 버튼**: Authorization 헤더 **포함**해서 호출 → 전 디바이스.

```ts
// 일반
async logoutThisDevice() {
  await fetch(`${API_BASE}/api/v1/auth/logout`, {
    method: "POST",
    credentials: "include"
  })
  this.clearMemory()
}

// 전체
async logoutAllDevices() {
  await fetch(`${API_BASE}/api/v1/auth/logout`, {
    method: "POST",
    credentials: "include",
    headers: { Authorization: `Bearer ${this.accessToken}` }
  })
  this.clearMemory()
}
```

---

## 6. 자동 토큰 갱신 (Auto-refresh)

API 호출 중 access가 만료되면 401이 떨어진다. 클라이언트는 **투명하게 refresh를 시도하고 원래 요청을 재실행**해야 한다. axios interceptor 또는 fetch wrapper로 구현한다.

### 6.1 핵심 규칙
1. **단일 큐**: 동시에 여러 요청이 401을 받아도 `/refresh`는 **딱 한 번만** 호출. 나머지는 그 결과를 기다린다. 이걸 안 하면 race로 family가 폭파된다(§9).
2. **재시도는 1회만**: 갱신 후에도 401이면 그땐 정말 로그아웃.
3. `/refresh` 자체가 401이면 곧장 로그아웃 처리.

### 6.2 axios 예시

```ts
const api = axios.create({ baseURL: API_BASE, withCredentials: true })

api.interceptors.request.use((config) => {
  const token = authService.getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let refreshPromise: Promise<void> | null = null

api.interceptors.response.use(
  (res) => res,
  async (err) => {
    const original = err.config
    if (err.response?.status !== 401 || original._retried) {
      return Promise.reject(err)
    }
    original._retried = true

    if (!refreshPromise) {
      refreshPromise = authService.tryRefresh()
        .catch((e) => { authService.clearMemory(); throw e })
        .finally(() => { refreshPromise = null })
    }
    try {
      await refreshPromise
      original.headers.Authorization = `Bearer ${authService.getAccessToken()}`
      return api(original)
    } catch {
      window.location.href = "/login"
      return Promise.reject(err)
    }
  }
)
```

### 6.3 사전 갱신(Proactive refresh) — 선택적

매번 401을 기다리지 않고 만료 1분 전에 미리 갱신해도 된다. 다만 단순 인터셉터만으로 충분히 부드러운 UX가 나오므로 처음엔 안 해도 무방.

```ts
// 옵션: 13분마다 갱신 (15min - 2min 버퍼)
setInterval(() => {
  if (authService.isLoggedIn()) authService.tryRefresh().catch(() => {})
}, 13 * 60 * 1000)
```

---

## 7. 보호된 API 호출

```ts
const res = await api.get("/api/v1/error-cases")
// 인터셉터가 Bearer 헤더를 자동 부착
```

응답 401이면 §6의 자동 갱신이 동작.

### 7.1 프로필 표시 (avatar 포함)

로그인 직후나 새로고침 후 세션 복원 직후, `/api/v1/users/me`로 프로필을 한 번 가져와 캐시한다. avatar는 GitHub CDN 호스트(`https://avatars.githubusercontent.com/...`)이므로 `<img>` `src`에 그대로 바인딩하면 된다.

```ts
async function loadMyProfile() {
  const res = await api.get("/api/v1/users/me")
  return res.data  // { userId, email, displayName, avatarUrl, status }
}
```

```tsx
function ProfileBadge({ profile }) {
  return (
    <div>
      {profile.avatarUrl && <img src={profile.avatarUrl} alt="" width={32} height={32} />}
      <span>{profile.displayName}</span>
    </div>
  )
}
```

**CSP 사용 시**: `Content-Security-Policy`에 `img-src https://avatars.githubusercontent.com` 허용 필요.

### 7.2 마이페이지 프로필 수정 (`PATCH /me`)

```ts
async function updateProfile(patch: {
  displayName?: string
  avatarUrl?: string
  bio?: string
  clearAvatar?: boolean
  clearBio?: boolean
}) {
  const res = await api.patch("/api/v1/users/me", patch)
  return res.data
}
```

규칙:
- 필드를 **보내지 않으면**(=undefined/제거) 변경하지 않음.
- avatar/bio를 **비우려면** `clearAvatar: true` / `clearBio: true`를 명시. (JSON에서 "필드 누락"과 `null`을 구분 못 하는 환경 대응)
- displayName 1~100자, avatarUrl `http(s)://` prefix + 1024자 이하, bio 280자 이하.

```tsx
function ProfileEditor({ profile, onSaved }) {
  const [displayName, setName] = useState(profile.displayName)
  const [avatarUrl, setAvatar] = useState(profile.avatarUrl ?? "")
  const [bio, setBio] = useState(profile.bio ?? "")

  const save = async () => {
    const patch: any = {}
    if (displayName !== profile.displayName) patch.displayName = displayName
    if (avatarUrl !== (profile.avatarUrl ?? "")) {
      if (avatarUrl === "") patch.clearAvatar = true
      else patch.avatarUrl = avatarUrl
    }
    if (bio !== (profile.bio ?? "")) {
      if (bio.trim() === "") patch.clearBio = true
      else patch.bio = bio
    }
    if (Object.keys(patch).length === 0) return
    const updated = await updateProfile(patch)
    onSaved(updated)
  }
  // ...
}
```

### 7.3 rememberMe 체크박스 / 자동 로그인

- 로그인 페이지 체크박스 상태를 `startGithubLogin` → `finishGithubLogin`으로 전달.
- 콜백 시 본문 `rememberMe`에 그대로 실어 보내면 서버가 쿠키 정책을 결정.
- "이 디바이스에서 자동 로그인 OFF"를 사용자가 원하면 → 단순히 명시적 로그아웃 한 번. family revoke + 쿠키 만료 → 자동 로그인 비활성화.

```tsx
function LoginPage() {
  const [rememberMe, setRememberMe] = useState(false)
  return (
    <>
      <label>
        <input type="checkbox" checked={rememberMe} onChange={e => setRememberMe(e.target.checked)} />
        자동 로그인
      </label>
      <button onClick={() => authService.startGithubLogin(rememberMe)}>GitHub으로 시작하기</button>
    </>
  )
}
```

`startGithubLogin`은 rememberMe를 sessionStorage 같은 임시 저장소에 보관해뒀다가, 콜백 페이지에서 `finishGithubLogin`에 함께 전달하는 게 가장 단순:

```ts
async startGithubLogin(rememberMe: boolean) {
  sessionStorage.setItem("iam.rememberMe", String(rememberMe))
  // ... POST /authorize → window.location.href = authorizationUrl
}

// callback 페이지
const rememberMe = sessionStorage.getItem("iam.rememberMe") === "true"
sessionStorage.removeItem("iam.rememberMe")
await authService.finishGithubLogin(code, state, rememberMe)
```

---

## 8. 에러 처리 매트릭스

| 상황 | HTTP | 응답 본문 | FE 대응 |
|---|---|---|---|
| `/authorize`에서 미지원 provider | 400 | `ProblemDetail` | "지원하지 않는 로그인 방식" 토스트 |
| 콜백 state 불일치 | 401 | `authentication_failed` | 처음부터 다시 ("세션이 만료되었습니다") |
| GitHub code 만료/오타 | 401 | `authentication_failed` | 동일 |
| 사용자가 GitHub에서 거부 | (FE 단계, GitHub redirect에 `?error=access_denied`) | - | 로그인 페이지로 복귀 |
| `/refresh` 쿠키 없음 | 401 | `invalid_refresh_token` | 비로그인 상태로 처리 |
| `/refresh` 토큰 reuse 감지 | 401 | `invalid_refresh_token` | 강제 로그아웃 + 안내 ("다른 곳에서 사용된 흔적 감지") |
| access JWT 만료 | 401 | (Spring Security 기본) | 인터셉터가 refresh 후 재시도 |
| access JWT 서명 위조 | 401 | 동일 | 동일 처리(보통 refresh가 실패하며 강제 로그아웃) |
| 사용자 SUSPENDED | 401 | `authentication_failed` | "계정이 사용 중지되었습니다" 안내 |

---

## 9. 흔히 빠지는 함정

### 9.1 `credentials: "include"` 누락
쿠키가 안 가서 모든 요청이 401. 가장 흔한 실수.

### 9.2 CORS `allowCredentials=true` 누락
같은 이유로 쿠키 미전송. 백엔드 CORS 설정에 명시 필요. 그리고 `Access-Control-Allow-Origin: *` 와 `allowCredentials=true`는 **공존 불가** — 명시적 origin 화이트리스트 필요.

### 9.3 access token을 localStorage에 저장
XSS 한 방으로 도난. 본 시스템은 **메모리만** 사용한다.

### 9.4 새로고침마다 로그인 풀림
앱 부팅 시 `/refresh`를 호출하지 않은 경우. §4.1 참고.

### 9.5 동시 401 → family 폭파
auto-refresh를 단일 큐로 묶지 않으면, 동시에 두 요청이 만료를 만나 두 번 `/refresh`를 호출 → 두 번째가 이미 revoke된 토큰을 들고 와 reuse로 감지 → 사용자가 강제 로그아웃됨. **§6.2의 `refreshPromise` 패턴 필수**.

### 9.6 redirectUri 불일치
`/authorize`와 `/callback` 호출 시의 `redirectUri`, GitHub OAuth App 설정의 callback URL 세 군데가 **완전히 일치**해야 한다. 슬래시 하나, 쿼리스트링 하나 다르면 거부됨.

### 9.7 state 쿠키 path
`iam_oauth_state`는 path가 `/api/v1/auth/oauth`로 제한돼 있다. callback 호출 경로가 그 prefix에 포함돼야 쿠키가 전송됨. (현재 구현은 그렇게 돼있음.)

### 9.8 모바일 Safari의 SameSite
서드파티 쿠키 정책으로 `SameSite=Lax`가 일부 케이스에서 차단될 수 있음 → 가능하면 프론트와 iam-api를 **같은 부모 도메인**의 서브도메인으로 두는 게 가장 안전.

---

## 10. 시퀀스 다이어그램 (회원가입+로그인 통합)

```
User    Browser           FE App           iam-api          GitHub
 │        │                  │                │                │
 │ click  │                  │                │                │
 │───────▶│                  │                │                │
 │        │ POST /authorize  │                │                │
 │        │─────────────────▶│                │                │
 │        │                  │ POST /authorize│                │
 │        │                  │───────────────▶│                │
 │        │                  │  200 + state쿠키│                │
 │        │                  │◀───────────────│                │
 │        │ 302 → GitHub URL │                │                │
 │        │◀─────────────────│                │                │
 │        │                                    GET /login/oauth/authorize
 │        │──────────────────────────────────────────────────▶ │
 │        │                                                    │
 │ 로그인+승인                                                  │
 │───────▶│                                                    │
 │        │  302 → /oauth/github/callback?code=…&state=…        │
 │        │◀──────────────────────────────────────────────────  │
 │        │ load callback page                                  │
 │        │─────────────────▶│                                  │
 │        │                  │ POST /callback {code,state,uri}  │
 │        │                  │───────────────▶│                 │
 │        │                  │                │ POST /access_token
 │        │                  │                │────────────────▶│
 │        │                  │                │  access_token   │
 │        │                  │                │◀────────────────│
 │        │                  │                │ GET /user, /emails
 │        │                  │                │────────────────▶│
 │        │                  │                │  user profile   │
 │        │                  │                │◀────────────────│
 │        │                  │                │ INSERT user/identity
 │        │                  │                │ INSERT refresh(family=F1)
 │        │                  │ 200 + jwt + refresh쿠키 + state삭제
 │        │                  │◀───────────────│                 │
 │        │                  │ store jwt(메모리), navigate("/")  │
 │        │ render home     │                                   │
 │        │◀─────────────────│                                  │
```

---

## 11. 부록: iOS Apple Sign-In (예고)

같은 인프라에서 모바일은 흐름이 다르다. Apple은 OAuth code 교환 대신 **ID Token**을 모바일 앱에 직접 발급한다.

```
[1] iOS 앱: ASAuthorizationAppleIDProvider로 Sign in with Apple
[2] Apple: identityToken (JWT) 반환
[3] 앱 → iam-api: POST /api/v1/auth/oauth/apple/callback (가칭)
       { "idToken": "<apple identity token>" }
[4] iam-api(추후 구현): AppleProfileFetcher가 Apple JWKS로 idToken 서명·iss·aud·nonce 검증
       → SocialProfile 추출 → 기존 SocialLoginUseCase 흐름과 동일
[5] 응답 토큰을 모바일 앱이 keychain에 보관 (refresh 쿠키 대신 응답 본문 포함)
```

확장 포인트는 이미 마련돼 있다:
- `SocialProfileFetcherPort.ProviderCredential.IdToken` 케이스 활용.
- 새 `AppleProfileFetcher` 빈만 추가하면 라우팅은 자동.
- 모바일은 쿠키 대신 응답 본문에 refresh를 받도록 별도 컨트롤러를 두는 게 일반적(현재 미구현).

---

## 12. 체크리스트 (FE 구현 시 빠르게 확인용)

- [ ] 모든 인증 fetch에 `credentials: "include"` (axios면 `withCredentials: true`)
- [ ] iam-api CORS에 프론트 origin 화이트리스트 + `allowCredentials=true`
- [ ] GitHub OAuth App의 callback URL과 코드의 `redirectUri` 완전 일치
- [ ] access token은 메모리에만 보관 (localStorage/sessionStorage 금지)
- [ ] 앱 부팅 시 `/refresh` 1회 호출로 세션 복원
- [ ] 401 자동 갱신 인터셉터에 **단일 큐** 적용
- [ ] 로그아웃 시 메모리 클리어 + 라우팅
- [ ] 콜백 페이지에서 `?error=` 파라미터 처리(사용자 거부 등)
- [ ] (운영) HTTPS, 프론트와 iam-api 같은 부모 도메인, `Secure`/`SameSite=Lax` 쿠키 설정 확인
