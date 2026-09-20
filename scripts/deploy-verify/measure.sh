#!/usr/bin/env bash
# Recreate 배포 중단시간 / 기동실패 전면중단 / 롤백 복구시간 측정.
# 전제: k8s 컨텍스트 준비(예: k3d cluster create ev-deploy). app.yaml/prober.yaml 적용됨.
#   kubectl apply -f app.yaml -f prober.yaml && kubectl rollout status deploy/demo
set -uo pipefail
now(){ python3 -c 'import time;print(f"{time.time():.2f}")'; }
firstFail(){ awk -v b="${1%.*}" '{ts=$1+0} ts>=b && /FAIL/{print $1; exit}' "$2"; }
firstOK(){   awk -v r="${1%.*}" '{ts=$1+0} ts>=r && /OK/{print $1; exit}'   "$2"; }

echo "### A. 정상 배포(Recreate) 중단"
U=$(now); kubectl patch deploy/demo --type merge -p '{"spec":{"template":{"metadata":{"annotations":{"rev":"'$U'"}}}}}' >/dev/null
kubectl rollout status deploy/demo --timeout=120s >/dev/null; sleep 3
kubectl logs deploy/prober --tail=500 > /tmp/pA.log
FF=$(firstFail "$U" /tmp/pA.log); OKr=$(awk '/FAIL/{f=1} f&&/OK/{print $1;exit}' /tmp/pA.log)
python3 -c "print(f'  정상배포 중단창 ≈ {${OKr%.*}-${FF%.*}}s (초해상도)')"

echo "### B. 기동 실패 이미지 → 전면 중단 → 롤백 복구"
BAD=$(now); kubectl patch deploy/demo --type merge -p '{"spec":{"template":{"spec":{"containers":[{"name":"app","image":"busybox:1.36","args":null,"command":["sh","-c","exit 1"]}]}}}}' >/dev/null
sleep 20; kubectl get endpoints demo
RB=$(now); kubectl rollout undo deploy/demo >/dev/null; kubectl rollout status deploy/demo --timeout=120s >/dev/null; DONE=$(now)
sleep 3; kubectl logs deploy/prober --tail=800 > /tmp/pB.log
FF=$(firstFail "$BAD" /tmp/pB.log); RK=$(firstOK "$RB" /tmp/pB.log)
python3 -c "b=$BAD;rb=$RB;d=$DONE;print(f'  롤백 복구시간 {d-rb:.1f}s / 전면 중단 총 {d-b:.1f}s')"
