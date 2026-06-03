#!/usr/bin/env bash
# ===================================================================
# 로컬 개발/테스트용 JWT 발급 헬퍼 (의존성 없음 — openssl 만 사용).
# ⚠️ application-local.yml 의 dev 키를 사용한다. 운영 키 아님.
#
# 사용법:
#   scripts/mint-jwt.sh user [USER_ID] [ROLES_CSV]
#       → 게이트웨이용 사용자 JWT (HS256). gateway:8000 의 Authorization 헤더에 사용.
#         예) scripts/mint-jwt.sh user 123 USER
#
#   scripts/mint-jwt.sh internal <AUDIENCE> [USER_ID] [ROLES_CSV]
#       → 내부 JWT (RS256, gateway 개인키). core-api:8081 / iam-api:8080 직접 호출(격리/위조 테스트)용.
#         ⚠️ 수명 90초(60s TTL + 30s skew) — 만들고 바로 써야 함.
#         예) scripts/mint-jwt.sh internal core-api 123 USER
# ===================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY_LOCAL_YML="$REPO_ROOT/gateway/src/main/resources/application-local.yml"

# gateway/application-local.yml 의 HS256 사용자 시크릿과 동일해야 한다.
USER_SECRET='local-dev-secret-local-dev-secret-local-dev-secret-local-dev-secret-local-dev'

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

roles_to_json() {
  local csv="$1" out=""
  IFS=',' read -ra arr <<< "$csv"
  for r in "${arr[@]}"; do out+="\"$r\","; done
  printf '[%s]' "${out%,}"
}

# yml 의 `private-key-pem: |` 블록에서 PEM 을 추출(단일 진실원천 — 키 중복 없음).
extract_gateway_privkey() {
  awk '
    /private-key-pem:/ { f=1; next }
    f && /BEGIN PRIVATE KEY/ { p=1 }
    p { line=$0; sub(/^[[:space:]]+/,"",line); print line }
    p && /END PRIVATE KEY/ { exit }
  ' "$GATEWAY_LOCAL_YML"
}

mint_user() {
  local uid="${1:-123}" roles_csv="${2:-USER}"
  local exp=$(( $(date +%s) + 3600 ))
  local header='{"alg":"HS256","typ":"JWT"}'
  local payload
  payload="{\"iss\":\"https://iam.local\",\"aud\":[\"local-clients\"],\"sub\":\"$uid\",\"roles\":$(roles_to_json "$roles_csv"),\"typ\":\"access\",\"exp\":$exp}"
  local h p sig
  h=$(printf '%s' "$header" | b64url)
  p=$(printf '%s' "$payload" | b64url)
  sig=$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$USER_SECRET" -binary | b64url)
  printf '%s.%s.%s\n' "$h" "$p" "$sig"
}

mint_internal() {
  local aud="${1:?audience required (core-api|iam-api)}" uid="${2:-123}" roles_csv="${3:-USER}"
  local now exp; now=$(date +%s); exp=$(( now + 90 ))
  local header='{"alg":"RS256","typ":"JWT","kid":"gateway-local"}'
  local payload
  payload="{\"iss\":\"gateway\",\"aud\":[\"$aud\"],\"sub\":\"$uid\",\"roles\":$(roles_to_json "$roles_csv"),\"iat\":$now,\"exp\":$exp}"
  local keyfile; keyfile=$(mktemp)
  extract_gateway_privkey > "$keyfile"
  local h p sig
  h=$(printf '%s' "$header" | b64url)
  p=$(printf '%s' "$payload" | b64url)
  sig=$(printf '%s' "$h.$p" | openssl dgst -sha256 -sign "$keyfile" -binary | b64url)
  rm -f "$keyfile"
  printf '%s.%s.%s\n' "$h" "$p" "$sig"
}

case "${1:-}" in
  user)     shift; mint_user "$@" ;;
  internal) shift; mint_internal "$@" ;;
  *) echo "usage: $0 {user [USER_ID] [ROLES_CSV] | internal <AUDIENCE> [USER_ID] [ROLES_CSV]}" >&2; exit 1 ;;
esac
