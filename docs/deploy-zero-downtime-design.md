# 무중단 배포 개선 설계 — RollingUpdate + replicas≥2

> 2026-09-20 · 대상 `origin/alpha` · 현재 전략: 모든 서비스 `Recreate` + `replicas:1`
>
> 목적: 배포 시 발생하는 **수십 초~수 분의 다운타임**과 **기동 실패 이미지 배포 시 전면 중단**을 제거한다.
> 아래 수치는 실 매니페스트와 동일한 특성으로 k3d 에서 재현·측정한 값이다.

---

## 1. 왜 바꾸나 — 측정된 문제

| 상황 | 현재 (Recreate + replicas1) | 개선 (RollingUpdate + maxUnavailable:0) |
|---|---|---|
| **정상 배포 다운타임** | **≈ 22s** (스탠드인) / 프로덕션 **≈ 40s~3분**(+JVM 콜드스타트) | **≈ 0s** (기존 파드가 새 파드 Ready 까지 계속 서빙) |
| **기동/헬스체크 실패 배포** | **전면 중단 ≈ 42s+** (롤백까지 서비스 죽음) | **0s 중단** (기존 파드 유지, 롤아웃만 안전하게 정체) |
| **파드/노드 1개 장애** | 즉시 전면 중단(단일 파드) | 다른 레플리카가 계속 서빙(HA) |

- 근본 원인: `Recreate` 는 **기존 파드를 먼저 죽이고** 새 파드를 만든다. `replicas:1` 이라 그 사이 서비스가 완전히 빈다.
  여기에 readiness `initialDelaySeconds:20s` + JVM 콜드스타트(매니페스트 `startupProbe` 예산 25~150s)가 더해져 실제 다운타임이 길다.
- 측정 근거: `docs/record/verify-deploy-resilience-2026-09-20.md`, 재현 `scripts/deploy-verify/`.

---

## 2. 개선 설계 (핵심)

### 2-1. 배포 전략
```yaml
spec:
  replicas: 2                       # Phase 2 (HA). Phase 1 은 1 유지 가능 — §4 참조
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0             # 항상 기존 Ready 파드 수 유지 → 다운타임 0
      maxSurge: 1                   # 새 파드를 1개 더 띄워서 Ready 되면 교체
```
- **핵심 원리**: `maxUnavailable:0` + 정확한 `readinessProbe` = 새 파드가 Ready 되기 전엔 기존 파드가 트래픽을 계속 받는다.
  새 버전이 헬스체크에 실패하면 **영원히 Ready 가 안 되고 → 기존 파드가 계속 서빙 → 롤아웃이 멈출 뿐 중단은 없다.**
  (측정: bad 배포 시 ready-endpoints 가 내내 2 유지, 요청 실패 0.)

### 2-2. 진짜 0 을 위한 종료 처리 (endpoint 경합 제거)
정상 배포에서도 종료 중인 파드가 endpoints 에서 빠지기 전 짧게 in-flight 요청이 실패할 수 있다(측정: 71표본 중 4 blip).
이를 없애려면 **preStop 지연 + graceful 종료**:
```yaml
      terminationGracePeriodSeconds: 30
      containers:
        - name: <svc>
          lifecycle:
            preStop: { exec: { command: ["sh","-c","sleep 5"] } }   # endpoints 반영 대기 후 종료
```
> 스프링 graceful shutdown(`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase`)을 함께 켜면 in-flight 요청까지 정리된다.

### 2-3. 가용성 보호 (replicas≥2 일 때)
```yaml
# PodDisruptionBudget — 노드 드레인/자발적 중단 시 최소 1개 유지
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: <svc>-pdb }
spec:
  minAvailable: 1
  selector: { matchLabels: { app: <svc> } }
```
```yaml
# 2 레플리카를 다른 노드로 분산(단일노드면 자동 무시)
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: kubernetes.io/hostname
                labelSelector: { matchLabels: { app: <svc> } }
```

---

## 3. 서비스별 적용 가능성 (멀티레플리카 안전성) — **중요**

replicas≥2 로 올리려면 "같은 서비스가 2벌 동시에 도는" 상황이 안전해야 한다. 조사 결과:

| 서비스 | 배경 잡 중복 방지 | 스테이트풀 요소 | replicas≥2 판정 |
|---|---|---|---|
| **core-api** | OutboxRelayer/Cleanup/GC 전부 `@SchedulerLock` | 없음(스테이트리스) | ✅ 안전 |
| **iam-api** | OutboxRelayer/Cleanup, AccountDeletion `@SchedulerLock` | 없음 | ✅ 안전 |
| **insight-api** | DlqRetryJob/Cleanup `@SchedulerLock`, 소비 멱등 | 없음 | ✅ 안전 |
| **publish-api** | OutboxRelayer/Cleanup, IdempotencyCleanup `@SchedulerLock` | 없음 | ✅ 안전 |
| **noti-api** | DLQ 재시도는 `FOR UPDATE SKIP LOCKED` 로 분산 안전 | ⚠️ **SSE 브로커가 in-memory** | ⚠️ **주의 — §3-1** |

### 3-1. noti-api 는 특별 취급 필요
`NotificationSseBroker` 는 `ConcurrentHashMap<userId, SseEmitter>` 로 **각 파드 로컬 메모리**에 SSE 연결을 들고 있다.
`publishToUser(userId)` 는 **같은 JVM 의 연결에만** 도달한다.
→ replicas 2 로 늘리면, 알림을 처리한 파드와 사용자의 SSE 연결이 붙은 파드가 다를 때 **실시간 알림이 전달되지 않는다.**

**선택지**
- **(권장, 단기)** noti-api 는 **replicas:1 유지**하되 **전략만 RollingUpdate + maxSurge:1**로. → 배포 다운타임·기동실패 보호는 얻고, SSE fan-out 문제는 피한다.
  (롤아웃 중 잠깐 뜨는 새 파드로 옮겨간 SSE 연결은 옛 파드 종료 시 클라이언트가 자동 재연결 — EventSource 기본 동작.)
- **(중기)** SSE fan-out 도입 후 replicas≥2: 알림 이벤트를 **모든 noti 파드가 받도록**(예: 파드별 고유 consumer group, 또는 Redis/Kafka 브로드캐스트 채널) 바꿔 각 파드가 자기 로컬 연결에 전달.

---

## 4. 단계적 롤아웃 (권장 순서)

리소스가 넉넉하지 않은 환경(free-tier 등)을 고려해 **가장 싸고 안전한 것부터**.

- 각 파드 요청: cpu 100~150m / mem 384Mi(limit 640Mi). 5개 × 2레플리카 = 요청 합 ~1.9Gi mem + 700m cpu + 롤아웃 서지 여유. **노드 용량 확인이 전제.**

### Phase 1 — 전략만 RollingUpdate (replicas 그대로 1)
- `strategy: RollingUpdate {maxUnavailable:0, maxSurge:1}` + preStop.
- **추가 상시 리소스 0** (롤아웃 중에만 파드 +1). 
- 효과: **정상 배포 다운타임 ≈ 0**, **기동 실패 배포 시 전면 중단 제거**(새 파드 Ready 전엔 옛 파드 유지).
- 5개 서비스 전부 즉시 적용 가능(스테이트리스). noti 포함(§3-1 단기안과 동일).
- ⚠️ 전제: **backward-compatible 마이그레이션**(§5).

### Phase 2 — replicas:2 (HA)
- core/iam/insight/publish 부터 `replicas:2` + PDB + anti-affinity.
- 효과: 파드/노드 장애에도 무중단. 단 **상시 리소스 2배** → 노드 용량 확보 필요.
- noti-api 는 §3-1 중기안(SSE fan-out) 완료 후.

---

## 5. 하드 전제조건 — DB 마이그레이션 backward-compatible

`Recreate` 에서는 옛 버전이 죽은 뒤 새 버전이 뜨므로 두 버전이 겹치지 않았다.
**RollingUpdate 에서는 옛·새 버전이 잠시 동시에 산다.** 따라서 Flyway 마이그레이션은 **expand/contract** 로 나눠야 한다.
- ✅ 컬럼 추가(nullable/기본값), 인덱스 추가 — 안전
- ⚠️ 컬럼 삭제/이름변경/NOT NULL 강화 — **한 배포에서 하지 말 것**. (1) 새 컬럼 추가·양쪽 쓰기 → (2) 백필 → (3) 다음 배포에서 옛 컬럼 제거
- (참고) 여러 파드가 동시에 뜨면 Flyway 락으로 마이그레이션은 직렬화되어 **중복 실행은 안전**. 문제는 "스키마-코드 호환"이지 동시성이 아니다.

---

## 6. 구체 변경 위치

- **base**: `docs/deploy/kustomize/base/<svc>/deployment.yaml` 의 `strategy` 교체(+ preStop/terminationGracePeriod). Phase 2 에서 `replicas` 상향.
- **PDB/anti-affinity**: base 에 `pdb.yaml` 추가 + `kustomization.yaml` resources 등록, deployment 에 affinity.
- **overlay(stg)**: 변경 없음(이미지 태그 갱신 로직 그대로).
- **ArgoCD**: 매니페스트 커밋 → 기존대로 자동 sync. 첫 적용 시 파드 교체가 곧 무중단 롤링으로 수행됨.

> 공통값이라 base 에 두는 게 맞지만, noti-api 만 replicas 를 달리 가져가야 하므로(§3-1) **서비스별 base 에서 개별 지정**한다.

---

## 7. 롤백·운영 영향

- **좋아지는 점**: 기동 실패 이미지는 롤아웃이 "성공"으로 뒤집히지 않고 **정체**한다 → `kubectl rollout status` 가 실패로 뜨고, 옛 버전이 계속 서빙 → **긴급 롤백의 압박이 줄어든다.**
- **주의**: 롤아웃이 정체하면 `progressDeadlineSeconds`(기본 600s) 후 실패 표시. 알람을 걸어 "정체=배포 실패"를 인지할 것.
- **롤백 절차**는 기존과 동일(`docs/deploy-runbook.md` §3): git 이미지 태그 되돌림(ArgoCD) 또는 `kubectl rollout undo`.

---

## 8. 재현·측정 방법

`scripts/deploy-verify/` — `app-rolling.yaml`(개선안), `app.yaml`(현재 Recreate), `prober.yaml`, `measure.sh`.
```bash
k3d cluster create ev-deploy --servers 1 --agents 0 --no-lb --k3s-arg "--disable=traefik@server:0"
kubectl apply -f scripts/deploy-verify/app-rolling.yaml -f scripts/deploy-verify/prober.yaml
# 정상 롤링: rev 변경 후 prober FAIL 수 확인(≈0)
# 기동실패: 새 파드 readiness 를 죽은 포트로 patch → ready-endpoints 가 계속 유지되고 prober FAIL 0 확인
k3d cluster delete ev-deploy
```

---

## 9. 요약 (의사결정용)
- **지금 당장(Phase 1)**: 5개 서비스 전략을 `RollingUpdate{maxUnavailable:0,maxSurge:1}` + preStop 으로. **상시 비용 0, 다운타임·기동실패중단 제거.** 전제: backward-compatible 마이그레이션 규율.
- **다음(Phase 2)**: core/iam/insight/publish `replicas:2` + PDB + anti-affinity(노드 용량 확보 후). HA 확보.
- **noti-api**: SSE fan-out 해결 전까지 replicas:1 유지(전략만 RollingUpdate).
