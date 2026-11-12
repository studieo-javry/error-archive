#!/usr/bin/env bash
# =============================================================================
# Agent Capture — 로컬 헤드리스 E2E 오케스트레이터
#
# 실제 체인(로컬 AI 도구 → gateway /api/v1/agent/** → iam introspection → core-api → Postgres)을
# 로컬에서 띄우고, 교차언어 계약 테스트(test-e2e/)를 실제 스택에 대고 돌린다. OAuth·이메일 불필요
# (iam 의 local 전용 /__dev/auth 로 사용자 JWT 발급).
#
# 사용:
#   ./e2e/run-local-e2e.sh            # 기동 → 토큰 발급 → test:e2e → 테어다운
#   KEEP_UP=1 ./e2e/run-local-e2e.sh  # 테스트 후 서비스 유지(수동 Claude Code 연동용). eaa_ 토큰·URL 출력
#
# 전제: docker, openssl, node/npm, 그리고 리포 루트에서 ./gradlew 사용 가능.
# 비밀값: core 내부서명 키(PEM)는 런타임 생성. 나머지는 리포에 이미 있는 local 전용 더미값.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(cd "$HERE/.." && pwd)"                 # tools/error-archive-agent
REPO_ROOT="$(cd "$PKG_DIR/../.." && pwd)"          # 모노레포 루트
LOG_DIR="$HERE/.logs"
mkdir -p "$LOG_DIR"

GATEWAY_URL="http://localhost:8000"
IAM_URL="http://localhost:8080"
CORE_URL="http://localhost:8081"

# 격리된 compose 프로젝트 — 개발자의 평상시 core-api/iam-api 볼륨을 건드리지 않고, 매 실행 새 스키마.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-ea-e2e}"

# --- 로컬 전용 env (gateway application-local.yml 과 동일한 더미 시크릿) -------------------
export SPRING_PROFILES_ACTIVE=local
export IAM_JWT_SECRET="${IAM_JWT_SECRET:-local-dev-secret-local-dev-secret-local-dev-secret-local-dev-secret-local-dev}"
export IAM_DB_URL="${IAM_DB_URL:-jdbc:postgresql://localhost:5432/iam_db}"
export IAM_DB_USERNAME="${IAM_DB_USERNAME:-iam}"
export IAM_DB_PASSWORD="${IAM_DB_PASSWORD:-iam_local_pw}"
export CORE_DB_PASSWORD="${CORE_DB_PASSWORD:-core_local_pw}"
export CORE_ATTACHMENT_S3_ACCESS_KEY="${CORE_ATTACHMENT_S3_ACCESS_KEY:-minioadmin}"
export CORE_ATTACHMENT_S3_SECRET_KEY="${CORE_ATTACHMENT_S3_SECRET_KEY:-minioadmin}"
export NOTI_PUBLISHER_MODE="${NOTI_PUBLISHER_MODE:-http}"   # Kafka 회피(Noop 발행)
# iam 의 메일/OAuth 는 에이전트 흐름에 미사용 — 더미로 채워 boot 만 통과
export GITHUB_OAUTH_CLIENT_ID="${GITHUB_OAUTH_CLIENT_ID:-dummy}"
export GITHUB_OAUTH_CLIENT_SECRET="${GITHUB_OAUTH_CLIENT_SECRET:-dummy}"
export IAM_MAIL_HOST="${IAM_MAIL_HOST:-localhost}"
export IAM_MAIL_PORT="${IAM_MAIL_PORT:-1025}"
export IAM_MAIL_USERNAME="${IAM_MAIL_USERNAME:-}"
export IAM_MAIL_PASSWORD="${IAM_MAIL_PASSWORD:-}"
export IAM_MAIL_SMTP_AUTH="${IAM_MAIL_SMTP_AUTH:-false}"
export IAM_MAIL_STARTTLS="${IAM_MAIL_STARTTLS:-false}"
export IAM_EMAIL_PROVIDER="${IAM_EMAIL_PROVIDER:-smtp}"
export IAM_EMAIL_FROM_ADDRESS="${IAM_EMAIL_FROM_ADDRESS:-noreply@error-archive.local}"
export IAM_EMAIL_FROM_NAME="${IAM_EMAIL_FROM_NAME:-Error Archive (local)}"

# core 내부서명 키(issuer bean parse 용). 에이전트 경로는 core→iam 호출이 없어 iam 등록 공개키와
# 일치할 필요 없음 — 유효한 RSA PKCS#8 이면 충분. 미지정 시 생성.
if [[ -z "${CORE_INTERNAL_AUTH_PRIVATE_KEY_PEM:-}" ]]; then
  CORE_INTERNAL_AUTH_PRIVATE_KEY_PEM="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
  export CORE_INTERNAL_AUTH_PRIVATE_KEY_PEM
fi

PIDS=()
cleanup() {
  if [[ "${KEEP_UP:-0}" == "1" ]]; then return; fi
  echo "[e2e] 테어다운…"
  for pid in "${PIDS[@]:-}"; do [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true; done
  docker rm -f ea-e2e-s3mock >/dev/null 2>&1 || true
  docker compose -f "$REPO_ROOT/iam-api/docker-compose.yml" down -v >/dev/null 2>&1 || true
  docker compose -f "$REPO_ROOT/core-api/docker-compose.yml" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_http() { # URL  EXPECTED_CODES(공백구분)  TIMEOUT_S  NAME
  local url="$1" codes="$2" timeout="$3" name="$4" start; start=$(date +%s)
  while true; do
    local code; code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
    for c in $codes; do [[ "$code" == "$c" ]] && { echo "[e2e] $name 준비됨($code)"; return 0; }; done
    (( $(date +%s) - start > timeout )) && { echo "[e2e] $name 대기 초과(마지막 $code)"; return 1; }
    sleep 2
  done
}

echo "[e2e] 1) 인프라(docker) 기동"
docker compose -f "$REPO_ROOT/iam-api/docker-compose.yml" up -d
if [[ "${E2E_S3_MODE:-minio}" == "s3mock" ]]; then
  # 폴백: minio/* 이미지를 풀 수 없는 레지스트리 제한 환경. core compose 는 postgres+redis 만 올리고
  # S3 는 adobe/s3mock(S3 호환, 인메모리)으로 대체. 업로드 계약(putObject)은 동일하게 검증된다.
  docker compose -f "$REPO_ROOT/core-api/docker-compose.yml" up -d postgres redis
  docker rm -f ea-e2e-s3mock >/dev/null 2>&1 || true
  docker run -d --name ea-e2e-s3mock -p 9000:9090 \
    -e initialBuckets=error-archive-attachments adobe/s3mock:latest >/dev/null
  # initialBuckets 가 s3mock 버전에 따라 무시될 수 있어, 버킷을 S3 API 로 명시 생성(멱등). 실제 MinIO
  # 경로에선 minio-init 이 담당하는 역할.
  wait_http "http://localhost:9000/" "200 403 404" 60 "s3mock"
  curl -s -o /dev/null -X PUT "http://localhost:9000/error-archive-attachments" || true
else
  docker compose -f "$REPO_ROOT/core-api/docker-compose.yml" up -d
fi

echo "[e2e] 2) 서비스 빌드(assemble) — 최초엔 몇 분 소요"
( cd "$REPO_ROOT" && ./gradlew -q :iam-api:assemble :core-api:assemble :gateway:assemble )

echo "[e2e] 3) iam/core/gateway bootRun(백그라운드)"
( cd "$REPO_ROOT" && ./gradlew :iam-api:bootRun )  >"$LOG_DIR/iam.log"     2>&1 & PIDS+=($!)
( cd "$REPO_ROOT" && ./gradlew :core-api:bootRun )  >"$LOG_DIR/core.log"    2>&1 & PIDS+=($!)
( cd "$REPO_ROOT" && ./gradlew :gateway:bootRun )   >"$LOG_DIR/gateway.log"2>&1 & PIDS+=($!)

echo "[e2e] 4) 헬스 대기"
wait_http "$IAM_URL/__dev/auth/issue-access?userId=1" "200 401 405" 180 "iam"   # POST 전용 → GET 405 면 기동됨
wait_http "$CORE_URL/api/v1/agent/me"                "401 403 500" 180 "core"  # 직접호출은 X-Internal-Auth 없음→거부(엔트리포인트 500 포함)=기동
wait_http "$GATEWAY_URL/api/v1/agent/me"             "401" 120 "gateway"     # 게이트웨이 에이전트 체인

echo "[e2e] 5) 사용자 JWT 발급 → 에이전트 연결 생성 → eaa_ 토큰"
pick() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(String(JSON.parse(s)["'"$1"'"]??""))}catch{process.stdout.write("")}})'; }
ACCESS_RAW="$(curl -s -X POST "$IAM_URL/__dev/auth/issue-access?userId=1")"
ACCESS="$(printf '%s' "$ACCESS_RAW" | pick accessToken)"
[[ -n "$ACCESS" ]] || { echo "[e2e] 사용자 JWT 발급 실패. 응답: $ACCESS_RAW"; exit 1; }
# 연결 생성은 게이트웨이 경유 — 게이트웨이가 사용자 JWT(HS256)를 검증해 iam 으로 내부 JWT 를 주입한다.
# (iam 직접 호출은 /api/v1/me/** 가 내부 JWT 를 기대해 401 MISSING_TOKEN)
CONN_RAW="$(curl -s -X POST "$GATEWAY_URL/api/v1/me/agent-connections" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"label":"local-e2e","scopes":["case:read","capture:read","capture:write","artifact:write"]}')"
EAA="$(printf '%s' "$CONN_RAW" | pick token)"
[[ "$EAA" == eaa_* ]] || { echo "[e2e] eaa_ 토큰 발급 실패. 연결생성 응답: $CONN_RAW"; exit 1; }
echo "[e2e] eaa_ 토큰 발급 OK (${EAA:0:8}…)"

echo "[e2e] 6) 계약 테스트 실행(test:e2e)"
export E2E_BASE_URL="$GATEWAY_URL"
export E2E_AGENT_TOKEN="$EAA"
set +e
( cd "$PKG_DIR" && npm run --silent test:e2e )
RC=$?
set -e

echo "[e2e] 완료(rc=$RC)"
if [[ "${KEEP_UP:-0}" == "1" ]]; then
  echo "[e2e] KEEP_UP=1 — 서비스 유지. 수동 연동:"
  echo "       export ERROR_ARCHIVE_BASE_URL=$GATEWAY_URL"
  echo "       export ERROR_ARCHIVE_AGENT_TOKEN=$EAA"
  echo "       node $PKG_DIR/dist/index.js doctor"
  trap - EXIT   # 유지
fi
exit $RC
