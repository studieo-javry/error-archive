#!/usr/bin/env bash
# =============================================================================
# ARC(자동 문제해결 기록) 자발호출률 회귀 측정 — on-demand E2E.
#
# 실제 Claude Code(`claude -p` 헤드리스)에 이 커넥터(MCP+훅+Skill)를 붙이고 버그 있는 repo 에
# "테스트 고쳐줘"만 준다(Error Archive 언급 0). 모델이 **스스로** capture_* 를 불러 Capture 를
# 봉인하는지 집계해 자발 기록 성공률과 오생성률을 낸다. 넛지 커넥터 vs (선택) 베이스라인 커넥터 A/B.
#
# ⚠️ 모델 행동 측정이라 결정론적 CI 게이트가 아니다(비용·비결정성). 회귀 추적용 on-demand 도구.
#
# 전제: claude CLI, node, git, python3, 그리고 **이미 기동된 스택**(gateway + core + iam)과 eaa_ 토큰.
#   run-local-e2e.sh 를 KEEP_UP=1 로 띄우면 eaa_ 토큰·URL 을 출력한다.
#
# 사용:
#   E2E_AGENT_TOKEN=eaa_… ./e2e/arc-autorecord-e2e.sh
#   E2E_BASE_URL=http://localhost:8000 E2E_AGENT_TOKEN=eaa_… \
#     E2E_BASELINE_DIST=/path/to/old/dist/index.js \   # 있으면 베이스라인(넛지 OFF) A/B 도 측정
#     ARC_BUG_TRIALS=3 ARC_NOISE_TRIALS=2 ./e2e/arc-autorecord-e2e.sh
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(cd "$HERE/.." && pwd)"
DIST_NUDGE="$PKG_DIR/dist/index.js"
SKILLSRC="$PKG_DIR/skill/error-archive-recording/SKILL.md"

BASE="${E2E_BASE_URL:-http://localhost:8000}"
TOKEN="${E2E_AGENT_TOKEN:-}"
BASELINE_DIST="${E2E_BASELINE_DIST:-}"
BUG_TRIALS="${ARC_BUG_TRIALS:-3}"
NOISE_TRIALS="${ARC_NOISE_TRIALS:-2}"

fail() { echo "[arc-e2e] $*" >&2; exit 2; }
command -v claude >/dev/null || fail "claude CLI 없음"
command -v node   >/dev/null || fail "node 없음"
command -v python3 >/dev/null || fail "python3 없음"
[[ -f "$DIST_NUDGE" ]] || fail "커넥터 미빌드($DIST_NUDGE) — 먼저 npm run build"
[[ "$TOKEN" == eaa_* ]] || fail "E2E_AGENT_TOKEN(eaa_…) 필요"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/arc-e2e.XXXXXX")"
RESULTS="$WORK/results.tsv"
printf 'trial\tcondition\ttask\tcapture_start\tcapture_complete\toutcome\n' > "$RESULTS"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

BUG_VARIANTS=(add even maxof fact last)
write_bug() { # dir variant
  local d="$1" v="$2"
  case "$v" in
    add)   printf 'def add(a, b):\n    return a - b\n' > "$d/m.py"; printf 'from m import add\nassert add(2,3)==5, add(2,3)\nprint("PASS")\n' > "$d/t.py" ;;
    even)  printf 'def is_even(n):\n    return n %% 2 == 1\n' > "$d/m.py"; printf 'from m import is_even\nassert is_even(4) and not is_even(3)\nprint("PASS")\n' > "$d/t.py" ;;
    maxof) printf 'def max_of(xs):\n    return min(xs)\n' > "$d/m.py"; printf 'from m import max_of\nassert max_of([1,5,2])==5, max_of([1,5,2])\nprint("PASS")\n' > "$d/t.py" ;;
    fact)  printf 'def fact(n):\n    r = 1\n    for i in range(1, n):\n        r *= i\n    return r\n' > "$d/m.py"; printf 'from m import fact\nassert fact(4)==24, fact(4)\nprint("PASS")\n' > "$d/t.py" ;;
    last)  printf 'def last(xs):\n    return xs[0]\n' > "$d/m.py"; printf 'from m import last\nassert last([1,2,3])==3, last([1,2,3])\nprint("PASS")\n' > "$d/t.py" ;;
    ok)    printf 'def add(a, b):\n    return a + b\n' > "$d/m.py"; printf 'from m import add\nassert add(2,3)==5\nprint("PASS")\n' > "$d/t.py" ;;
  esac
}

run_trial() { # id condition(nudge|plain) task(bug|noise) variant prompt
  local id="$1" cond="$2" task="$3" variant="$4" prompt="$5"
  local dist="$DIST_NUDGE"; [[ "$cond" == plain ]] && dist="$BASELINE_DIST"
  local repo="$WORK/$id" cfg="$WORK/$id-cfg"
  mkdir -p "$repo/.claude/skills/error-archive-recording" "$cfg/queue"
  ( cd "$repo" && git init -q && git remote add origin "https://github.com/acme/arc-$id.git" && git config user.email t@local && git config user.name t )
  write_bug "$repo" "$variant"
  ( cd "$repo" && git add -A && git commit -qm init )
  if [[ "$cond" == nudge ]]; then
    cp "$SKILLSRC" "$repo/.claude/skills/error-archive-recording/SKILL.md"
    printf '{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"UserPromptSubmit":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"PostToolUse":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"PostToolUseFailure":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"Stop":[{"hooks":[{"type":"command","command":"node %s hook"}]}]}}\n' "$dist" "$dist" "$dist" "$dist" "$dist" > "$repo/.claude/settings.json"
  else
    rm -rf "$repo/.claude/skills"
    printf '{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"PostToolUse":[{"hooks":[{"type":"command","command":"node %s hook"}]}],"Stop":[{"hooks":[{"type":"command","command":"node %s hook"}]}]}}\n' "$dist" "$dist" "$dist" > "$repo/.claude/settings.json"
  fi
  local mcp="$cfg/mcp.json"
  printf '{"mcpServers":{"error-archive":{"command":"node","args":["%s","serve"],"env":{"ERROR_ARCHIVE_BASE_URL":"%s","ERROR_ARCHIVE_AGENT_TOKEN":"%s","ERROR_ARCHIVE_STATE":"%s/projects.json","ERROR_ARCHIVE_CONFIG":"%s/config.json","ERROR_ARCHIVE_QUEUE_DIR":"%s/queue","ERROR_ARCHIVE_SESSION_CONTEXT":"%s/session-context.json"}}}}\n' "$dist" "$BASE" "$TOKEN" "$cfg" "$cfg" "$cfg" "$cfg" > "$mcp"
  ERROR_ARCHIVE_BASE_URL="$BASE" ERROR_ARCHIVE_AGENT_TOKEN="$TOKEN" ERROR_ARCHIVE_STATE="$cfg/projects.json" ERROR_ARCHIVE_CONFIG="$cfg/config.json" ERROR_ARCHIVE_QUEUE_DIR="$cfg/queue" ERROR_ARCHIVE_SESSION_CONTEXT="$cfg/session-context.json" \
    bash -c "printf 'y\n' | node '$dist' connect-project '$repo'" >/dev/null 2>&1
  local stream="$cfg/stream.jsonl"
  ( cd "$repo" && ERROR_ARCHIVE_BASE_URL="$BASE" ERROR_ARCHIVE_AGENT_TOKEN="$TOKEN" ERROR_ARCHIVE_STATE="$cfg/projects.json" ERROR_ARCHIVE_CONFIG="$cfg/config.json" ERROR_ARCHIVE_QUEUE_DIR="$cfg/queue" ERROR_ARCHIVE_SESSION_CONTEXT="$cfg/session-context.json" \
    timeout 300 claude -p "$prompt" --mcp-config "$mcp" --permission-mode acceptEdits \
      --allowedTools "Bash(python3:*)" "Bash(ls:*)" "Bash(cat:*)" "Bash(grep:*)" "Read" "Edit" "Write" "Glob" "Grep" "mcp__error-archive" \
      --output-format stream-json --verbose > "$stream" 2>/dev/null )
  python3 - "$stream" "$id" "$cond" "$task" >> "$RESULTS" <<'PY'
import json,sys
stream,tid,cond,task=sys.argv[1:5]
s=c=0
try:
    for l in open(stream):
        l=l.strip()
        if not l: continue
        r=json.loads(l)
        if r.get('type')=='assistant':
            for b in r.get('message',{}).get('content',[]):
                if isinstance(b,dict) and b.get('type')=='tool_use':
                    if b['name'].endswith('capture_start'): s+=1
                    if b['name'].endswith('capture_complete'): c+=1
except Exception: pass
out=('SEALED' if c>=1 else 'STARTED_NO_SEAL' if s>=1 else 'NO_CAPTURE') if task=='bug' else ('NO_CAPTURE(ok)' if s==0 else 'OVERGEN(%d)'%s)
print('%s\t%s\t%s\t%d\t%d\t%s'%(tid,cond,task,s,c,out))
PY
  echo "[arc-e2e] $id/$cond/$task 완료"
}

FIX='Run `python3 t.py` in this directory — it currently fails. Find and fix the bug(s) in the source so it prints PASS.'
for i in $(seq 1 "$BUG_TRIALS"); do
  v="${BUG_VARIANTS[$(( (i-1) % ${#BUG_VARIANTS[@]} ))]}"
  run_trial "n$i" nudge bug "$v" "$FIX"
done
for i in $(seq 1 "$NOISE_TRIALS"); do
  if (( i % 2 == 1 )); then run_trial "q$i" nudge noise ok "Explain in one sentence what the add function in m.py does. Do not modify any files."
  else run_trial "q$i" nudge noise ok 'Run `python3 t.py` and tell me whether it passes. Do not change anything.'; fi
done
if [[ -n "$BASELINE_DIST" && -f "$BASELINE_DIST" ]]; then
  for i in $(seq 1 "$BUG_TRIALS"); do
    v="${BUG_VARIANTS[$(( (i-1) % ${#BUG_VARIANTS[@]} ))]}"
    run_trial "b$i" plain bug "$v" "$FIX"
  done
fi

echo; echo "=== 결과 ==="; column -t -s$'\t' "$RESULTS"
echo; echo "=== 집계 ==="
python3 - "$RESULTS" <<'PY'
import csv,sys
rows=list(csv.DictReader(open(sys.argv[1]),delimiter='\t'))
def g(cond,task): return [r for r in rows if r['condition']==cond and r['task']==task]
nb=g('nudge','bug'); pb=g('plain','bug'); noise=[r for r in rows if r['task']=='noise']
if nb:
    sealed=sum(1 for r in nb if r['outcome']=='SEALED')
    print('넛지 ON · 버그 자발 봉인 성공률: %d/%d = %d%%'%(sealed,len(nb),round(100*sealed/len(nb))))
if noise:
    over=sum(1 for r in noise if int(r['capture_start'])>0)
    print('넛지 ON · 노이즈 오생성률: %d/%d'%(over,len(noise)))
if pb:
    b=sum(1 for r in pb if int(r['capture_start'])>0)
    print('베이스라인 OFF · 버그 자발 호출: %d/%d'%(b,len(pb)))
PY
