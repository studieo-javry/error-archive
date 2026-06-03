#!/usr/bin/env bash
# ===================================================================
# 내부 서비스 인증 시나리오 러너 (로컬).
# 전제: gateway(:8000) / core-api(:8081) / iam-api(:8080) 가 *현재 빌드*로 떠 있어야 한다.
#       (stale 인스턴스면 결과가 옛 동작으로 나온다 — 재기동 필요.)
#
# 인증 경계만 검증한다(비즈니스 로직 아님): 존재하지 않는 첨부를 조회해
#   401 = 인증 실패 / 404 = 인증 통과 후 not-found 로 구분한다.
# ===================================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
MINT=scripts/mint-jwt.sh
EP="/api/v1/error-attachments/nonexistent-marker"

UJWT=$($MINT user 123 USER)
ICORE=$($MINT internal core-api 123 USER)
IIAM=$($MINT internal iam-api 123 USER)

pass=0; fail=0
check() { # $1=설명 $2=기대코드 $3...=curl args
  local desc="$1" want="$2"; shift 2
  local got; got=$(curl -s -o /dev/null -w "%{http_code}" "$@")
  if [[ "$got" == "$want" ]]; then printf "  ✅ %-45s %s\n" "$desc" "$got"; pass=$((pass+1))
  else                            printf "  ❌ %-45s got=%s want=%s\n" "$desc" "$got" "$want"; fail=$((fail+1)); fi
}

# 게이트웨이를 우회했을 떄 404가 나오는 게 적합할까? 내부망으로 인해 우회가 불가능하도록 네트워크 격리가 들어갈텐데
echo "── 직접 core-api:8081 (게이트웨이 우회) ──"
check "1) 토큰 없음 → 401"                    401  http://localhost:8081$EP
check "2) 평문 X-User-Id 위조 → 401"          401  -H "X-User-Id: 1" http://localhost:8081$EP
check "3) 유효 internal JWT(aud=core-api)→404" 404 -H "X-Internal-Auth: $ICORE" http://localhost:8081$EP
check "4) 잘못된 aud(iam-api) → 401"           401  -H "X-Internal-Auth: $IIAM" http://localhost:8081$EP
check "5) 변조 서명 → 401"                     401  -H "X-Internal-Auth: ${ICORE}tampered" http://localhost:8081$EP

echo "── 게이트웨이 경유 :8000 (gw→svc 정상 흐름) ──"
check "6) 사용자 JWT 없음 → 401"               401  http://localhost:8000$EP
check "7) 유효 사용자 JWT → 404(인증통과)"     404  -H "Authorization: Bearer $UJWT" http://localhost:8000$EP
check "8) 변조 사용자 JWT → 401"               401  -H "Authorization: Bearer ${UJWT}x" http://localhost:8000$EP

echo
echo "결과: PASS=$pass FAIL=$fail"
exit $(( fail > 0 ? 1 : 0 ))
