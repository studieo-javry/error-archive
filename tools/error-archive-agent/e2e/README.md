# Agent Capture — 로컬 E2E 검증

커넥터 단위 테스트(`test/`, 모의 fetch/InMemoryTransport)는 배선만 검증합니다. 이 디렉터리는 **실제
스택**(로컬 AI 도구 → gateway `/api/v1/agent/**` → iam introspection → core-api → Postgres)을 띄우고,
그 위에서 커넥터가 정말로 동작하는지 확인합니다.

```
Claude Code ─(MCP,stdio)→ error-archive-agent ─(HTTPS, eaa_)→ gateway:8000
                                                                   │ introspect(eaa_→사용자/연결/scope)
                                                                   ▼ iam:8080
                                                        internal RS256 JWT(cid/scp)
                                                                   ▼ core-api:8081 → Postgres
```

## 1. 자동 계약 E2E (권장, 헤드리스)

실제 Claude Code 없이 **TS `apiClient` ↔ 실행 중 gateway** 계약을 검증합니다(MCP 계층 제외).

```bash
cd tools/error-archive-agent
npm ci && npm run build
./e2e/run-local-e2e.sh          # 인프라+서비스 기동 → eaa_ 발급 → test:e2e → 테어다운
```

스크립트가 하는 일:
1. docker compose(`iam-api/`, `core-api/`)로 Postgres×2·Redis·MinIO 기동
2. `./gradlew :iam-api:bootRun :core-api:bootRun :gateway:bootRun` (8080/8081/8000)
3. iam local 전용 `POST /__dev/auth/issue-access?userId=1` 로 사용자 JWT 발급(**OAuth·이메일 불필요**)
4. `POST /api/v1/me/agent-connections` 로 `eaa_` 연결 토큰 발급
5. `E2E_BASE_URL`/`E2E_AGENT_TOKEN` 를 세팅하고 `npm run test:e2e` 실행

검증 범위(`test-e2e/contract.e2e.test.ts`): 정상 전 구간(start→get→evidence→attempt→solution→complete)
+ 오류흐름(401 / 409 REVISION_CONFLICT / 409 ARTIFACT_HASH_MISMATCH / 422 UNKNOWN_ATTEMPT_REFERENCE /
415 UNSUPPORTED_ARTIFACT / 멱등 재생성).

> 비밀값: core 내부서명 키(PEM)만 런타임 생성합니다. 나머지(JWT 시크릿·DB 비번·MinIO 키)는 리포에
> 이미 있는 **local 전용 더미값**입니다. `.env*`·`*.pem`·`e2e/.logs/` 는 gitignore 됩니다.

## 2. 실제 Claude Code 스모크 (수동, 릴리스 게이트)

자동 테스트가 못 덮는 "실제 MCP 클라이언트 ↔ 도구 인식 ↔ 웹 검토" 구간입니다.

```bash
KEEP_UP=1 ./e2e/run-local-e2e.sh       # 테스트 후 서비스 유지 + eaa_ 토큰/URL 출력
```

출력된 토큰으로 Claude Code 에 등록:

```bash
claude mcp add --scope local --transport stdio error-archive \
  -- node "$PWD/dist/index.js"
# 환경변수 ERROR_ARCHIVE_BASE_URL(http://localhost:8000) / ERROR_ARCHIVE_AGENT_TOKEN(eaa_…) 를
# MCP 서버 env 로 주입(토큰을 공유 설정/명령 히스토리에 평문으로 남기지 말 것).

claude mcp list && claude mcp get error-archive     # 도구 8종 인식 확인(/mcp 로도 확인)
```

스모크 시나리오:
1. `diagnose` 로 연결 확인
2. "테스트용 초안을 만들고 조회까지 확인, 봉인은 하지 마" → `capture_start`+`capture_get`
3. 로그 파일 첨부 + 시도/해결 후보 연결 → `capture_attach_evidence`/`add_attempt`/`add_solution`
4. **사용자가 명시 지시**로만 `capture_complete`(→ READY_FOR_REVIEW)
5. 웹 UI *에이전트 연결/검토*에서 봉인 초안 확인 → 기존 Case 로 반영(import)

## 전제

docker · openssl · node/npm · 리포 루트 `./gradlew`. 포트 8000/8080/8081·5432/5433·6379·9000 가 비어 있어야 함.

> **레지스트리 제한 환경 폴백**: `minio/*` 이미지를 풀 수 없으면 `E2E_S3_MODE=s3mock ./e2e/run-local-e2e.sh`
> 로 실행하세요. core compose 의 MinIO 대신 `adobe/s3mock`(S3 호환, 인메모리)을 9000 포트로 띄워
> 업로드 계약을 동일하게 검증합니다.

## 정리

```bash
docker compose -f ../../iam-api/docker-compose.yml down
docker compose -f ../../core-api/docker-compose.yml down
# KEEP_UP 으로 남긴 bootRun 은 각 프로세스를 직접 종료(Ctrl-C) 하거나 포트로 kill
```
