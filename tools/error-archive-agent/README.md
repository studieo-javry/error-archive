# error-archive-agent (MCP 커넥터)

Claude Code 등 로컬 AI 코딩 도구가 **개발 중 오류·해결 과정**을 error-archive 의 비공개 초안으로
수집하도록 돕는 [MCP](https://modelcontextprotocol.io) 서버입니다. 수집된 초안은 나중에 사용자가 웹에서
검토·확정하면 기존 Case/Step/Solution 으로 반영됩니다(검토 전에는 알림·검색·통계에 노출되지 않음).

> AI Agent Capture Assistant (#144 P0). 서버 측 계약은 게이트웨이 `/api/v1/agent/**` 입니다.

## 동작 개요

```
Claude Code  ──(MCP, stdio)──▶  error-archive-agent  ──(HTTPS, Bearer eaa_…)──▶  gateway /api/v1/agent/**
                                                                                      │ introspection
                                                                                      ▼  core-api(초안 저장)
```

커넥터는 **연결 토큰(`eaa_…`)만** 실어 보냅니다. 서버가 토큰을 사용자·연결·scope 로 해석하므로,
사용자 로그인(JWT)과는 분리됩니다. 파일 근거는 **실제 바이트를 업로드**하며(경로 문자열 금지), 서버가
형식·크기를 다시 검사합니다.

## 준비물

1. **연결 토큰 발급** — error-archive 웹의 *설정 › 에이전트 연결* 에서 새 연결을 만들고 토큰(`eaa_…`)을
   1회 복사합니다(다시 보여주지 않음). scope: `capture:read`, `capture:write`, `artifact:write`.
2. **게이트웨이 base URL** — 예: `https://api.error-archive.com`.

## 설치 / 빌드

```bash
cd tools/error-archive-agent
npm install
npm run build        # dist/ 생성
npm test             # 단위·MCP 배선 테스트
```

## 설정

환경변수(우선) 또는 설정 파일로 지정합니다.

| 항목 | 환경변수 | 설정 파일 키 |
| --- | --- | --- |
| 게이트웨이 base URL | `ERROR_ARCHIVE_BASE_URL` | `baseUrl` |
| 연결 토큰(`eaa_…`) | `ERROR_ARCHIVE_AGENT_TOKEN` | `token` |

설정 파일 경로: `$ERROR_ARCHIVE_CONFIG` 또는 `~/.config/error-archive-agent/config.json`

```json
{ "baseUrl": "https://api.error-archive.com", "token": "eaa_…" }
```

### 설치 환경 식별(연결 목록 표시용)

웹의 **연결 목록**에서 "어느 기기/도구"인지 알아보도록, 커넥터가 설치 메타를 매 요청에 함께 보냅니다
(`X-Agent-*` 헤더). 이 값은 **표시용**이며 서버 인가에는 쓰이지 않습니다.

| 항목 | 환경변수 | 기본값 |
| --- | --- | --- |
| 설치 라벨(사람이 읽는 이름) | `ERROR_ARCHIVE_INSTALL_LABEL` | 호스트명(`hostname`). 빈 문자열이면 전송 안 함(opt-out) |
| 설치 식별자(고정) | `ERROR_ARCHIVE_INSTALL_ID` | `~/.config/error-archive-agent/install.json` 에 1회 생성·보관(랜덤 UUID, 하드웨어 지문 아님) |

커넥터 종류(`claude-code`)·버전은 자동으로 실립니다. 토큰 재발급 등으로 연결을 다시 만들어도, 같은
설치는 목록에서 같은 라벨로 보입니다.

## 진단(doctor)

연결이 올바른지 먼저 확인하세요. 토큰은 마스킹돼 출력됩니다.

```bash
ERROR_ARCHIVE_BASE_URL=https://api.error-archive.com \
ERROR_ARCHIVE_AGENT_TOKEN=eaa_… \
node dist/index.js doctor
# ✓ 연결 OK  / userId / connectionId / scopes
```

## Claude Code 에 등록

`claude mcp add` 또는 MCP 설정에 아래처럼 등록합니다(토큰은 환경변수로 주입 — 설정 파일에 평문 저장 지양).

```json
{
  "mcpServers": {
    "error-archive": {
      "command": "node",
      "args": ["/absolute/path/to/tools/error-archive-agent/dist/index.js"],
      "env": {
        "ERROR_ARCHIVE_BASE_URL": "https://api.error-archive.com",
        "ERROR_ARCHIVE_AGENT_TOKEN": "eaa_…"
      }
    }
  }
}
```

## 제공 도구

| 도구 | 설명 |
| --- | --- |
| `diagnose` | 토큰이 어떤 사용자·연결·scope 로 해석되는지(진단) |
| `capture_start` | 새 OPEN 초안 생성. `captureUuid` 반환(이후 도구에 전달) |
| `capture_add_attempt` | 시도(조사·수정·검증) 1건 기록(결과→상태) |
| `capture_add_solution` | 해결 후보 기록(참조 시도 1개 이상) |
| `capture_attach_evidence` | 로컬 파일(스크린샷·로그·diff·JSON)을 근거로 업로드 |
| `capture_update` | 초안 기본 정보·서사 교체(`expectedRevision` 낙관 검사) |
| `capture_get` | 초안 현재 상태 조회 |
| `capture_complete` | 초안 봉인(READY_FOR_REVIEW). **자동 호출 금지** — 사용자 지시로만 |

### 기록 원칙(이슈 #144)

- **근거 없는 단정 금지**: 원인·검증 결과(PASSED 등)는 실제 실행 근거가 있을 때만. 재현/검증의
  `executed` 는 명시하지 않으면 `false`.
- 봉인(`capture_complete`)은 Stop Hook 등으로 **자동 호출하지 않습니다**. 미검토 기록이 함부로
  올라오지 않게, 사용자/정책이 명시적으로 부를 때만 호출하세요.

### 멱등성과 재시도

- 생성·봉인 계열 요청의 `Idempotency-Key` 는 **클라이언트 식별자에서 결정론적으로 파생**됩니다
  (`capture.create`←`captureUuid`, `attempt.add`←`captureUuid:clientAttemptId`,
  `solution.add`←`captureUuid:clientSolutionId`, `capture.complete`←`captureUuid`). 따라서 **같은
  식별자를 다시 넘기면** 서버가 재시도를 하나로 합쳐 중복 생성이 방지됩니다. 안전한 재시도를 원하면
  `capture_start` 가 돌려준 `captureUuid`, `capture_add_attempt` 가 돌려준 `clientAttemptId` 를
  그대로 재사용하세요. (식별자를 생략하면 매번 새로 발급되어 새 기록으로 간주됩니다.)
- 서버가 `409 CONCURRENT_REQUEST`(같은 키가 동시에 처리 중) 를 주면 커넥터가 **같은 키로 한 번
  재시도**합니다. 그 외 오류는 그대로 보고합니다.
- 근거 파일의 `clientArtifactId` 를 생략하면 **파일 경로 기반**으로 자동 생성됩니다. 파일명이 같아도
  경로가 다르면 서로 다른 ID 가 되어, 동명 파일의 거짓 충돌(`409 ARTIFACT_HASH_MISMATCH`)을
  피합니다. 같은 파일을 다시 올리면 같은 ID → 서버가 내용 해시로 중복 제거(200)합니다.

## 보안

- 토큰은 비밀입니다. 로그·에러 메시지에 원문을 싣지 않습니다(마스킹). 설정 파일보다 환경변수 주입을
  권장합니다.
- 폐기/만료된 토큰은 게이트웨이에서 이미 401 로 차단됩니다(웹 *에이전트 연결* 에서 즉시 폐기 가능).
- 서버가 첨부 형식(PNG/JPEG/WebP·UTF-8 텍스트/로그/diff·JSON)·크기(파일 10 MiB, 초안당 20개·총
  50 MiB)를 재검사합니다. 허용되지 않는 형식은 거부됩니다.

## 개발

```bash
npm run typecheck    # 타입 검사
npm run dev          # tsx 로 소스 직접 실행(빌드 없이)
npm run doctor       # tsx 로 doctor 실행
```

## API 계약 (OpenAPI)

커넥터가 호출하는 에이전트 API(`/api/v1/agent/**`)의 계약을 core-api 의 OpenAPI `agent` 그룹에서
스냅샷으로 떠서, TypeScript 타입을 생성해 커밋한다. Kotlin DTO ↔ TS 타입 드리프트를 CI 에서 감시한다.

- `openapi/agent-openapi.json` — core-api `agent` 그룹 스펙 스냅샷(게이트웨이 `/api-docs/agent` = core `/v3/api-docs/agent`).
- `src/generated/agent-api.ts` — 위 스냅샷에서 `openapi-typescript` 로 생성한 타입(수정 금지, 생성물).

```bash
# 백엔드(에이전트 DTO/엔드포인트) 변경 후 계약 갱신:
#   1) 백엔드 기동 — e2e/run-local-e2e.sh 참고 (KEEP_UP=1 로 띄워두면 됨)
npm run openapi:snapshot   # 실행 중 백엔드 → openapi/agent-openapi.json 갱신
npm run openapi:gen        # 스냅샷 → src/generated/agent-api.ts 재생성
#   3) 두 파일 함께 커밋

npm run openapi:check      # CI 와 동일: 스냅샷에서 재생성 후 커밋본과 다르면 실패(드리프트 감지)
```

> CI(`agent-connector`)는 `openapi:check` 로 **스냅샷↔생성타입** 드리프트를 막는다. **스냅샷↔실제 백엔드**
> 드리프트(DTO 변경 후 스냅샷 미갱신)는 백엔드를 띄워 `openapi:snapshot` 을 다시 돌려 맞춘다(수동).
