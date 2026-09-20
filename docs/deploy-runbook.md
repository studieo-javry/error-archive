# 배포 런북 (stg/alpha) — 준비·배포·롤백·장애 특성

> 대상: `origin/alpha`(=stg) · 네임스페이스 `apps` · GitOps(ArgoCD) + GitHub Actions
>
> 이 문서 하나로 **새 환경 준비 → 배포 → 롤백 → 장애 시 동작**을 처음 보는 사람도 따라올 수 있게 정리했습니다.
> 뒤쪽 "배포 특성" 절의 중단/복구 수치는 실제 k8s(k3d)에서 재현·측정한 값입니다.

---

## 0. 한 장 그림

```
 개발자 push (alpha)              GitHub Actions (release-stg.yml)                 클러스터(apps ns)
      │                    ┌───────────────────────────────────────┐                    │
      └───────────────────▶│ detect → test(게이트) → build-push → bump│                    │
        서비스 코드 변경     │  변경감지   테스트통과   ghcr push   overlay │  git commit         │
                            └───────────────────────────────────────┘   태그 갱신 ──▶ ArgoCD ─▶ 배포
                                       ▲                                           (자동 sync)
                                 테스트 실패 시 여기서 멈춤(이미지·배포 갱신 안 됨)
```

- **자동화된 부분**: 변경 서비스 감지 → **테스트 게이트** → arm64 이미지 빌드·ghcr push → overlay 이미지 태그 커밋 → ArgoCD 가 감지해 자동 배포. (실제 22회 이상 가동됨)
- **배포 전략**: 모든 서비스 `Deployment` = `strategy: Recreate`, `replicas: 1`.

---

## 1. 새 환경 준비 (최초 1회)

새 클러스터/네임스페이스에 이 서비스들을 처음 올릴 때 필요한 준비물입니다.

### 1-1. 네임스페이스 + 노드 라벨
```bash
kubectl create namespace apps
# 매니페스트가 nodeSelector: { role: apps } 를 요구 → 앱을 올릴 노드에 라벨 부여
kubectl label node <노드이름> role=apps
```

### 1-2. 시크릿 (git 밖에서 생성 — ArgoCD 관리 대상 아님)
각 서비스는 `envFrom.secretRef: <svc>-secrets` 로 비밀값을 주입받습니다. **git 에 없으므로 수동 생성**합니다.
```bash
# 예시 (실제 키는 각 서비스 application-prod.yml 의 ${ENV} 목록 참조)
kubectl -n apps create secret generic core-api-secrets \
  --from-literal=CORE_DB_URL=... --from-literal=CORE_DB_USERNAME=... --from-literal=CORE_DB_PASSWORD=... \
  --from-literal=KAFKA_BOOTSTRAP_SERVERS=... --from-literal=CORE_INTERNAL_ISSUER_PRIVATE_KEY="..." ...
# iam-api-secrets / noti-api-secrets / insight-api-secrets / publish-api-secrets 동일하게
```
> 필요한 키 목록은 각 서비스 `application-prod.yml` 의 `${...}` 플레이스홀더 전체입니다.
> (참고: `SPRING_PROFILES_ACTIVE=prod` 는 시크릿이 아니라 overlay 의 `configMapGenerator` 로 주입됩니다.)

### 1-3. 외부 의존 (배포와 별개로 준비)
- **DB**: 서비스별 PostgreSQL (Flyway 가 기동 시 스키마 적용). DB 5개.
- **Kafka**: `user-activity.v1` 등 토픽을 쓰는 브로커.
- (해당 시) 오브젝트 스토리지·SMTP 등.

### 1-4. ArgoCD Application 등록 (리포 밖 설정)
ArgoCD 가 이 리포의 `docs/deploy/kustomize/overlays/stg` 를 바라보게 등록합니다(내용 요지):
```yaml
# ArgoCD Application (클러스터의 argocd ns 에 등록)
source:
  repoURL: https://github.com/studieo-javry/error-archive.git
  targetRevision: alpha
  path: docs/deploy/kustomize/overlays/stg
destination: { namespace: apps }
syncPolicy: { automated: { prune: true, selfHeal: true } }
```
> ArgoCD 가 private repo 면 `repo-error-archive` 자격증명 시크릿이 필요합니다.

### 1-5. 첫 배포
`overlays/stg` 를 ArgoCD 가 sync 하면 5개 서비스가 생성됩니다. 상태 확인:
```bash
kubectl -n apps get deploy,pods
kubectl -n apps rollout status deploy/core-api
```

---

## 2. 배포하기

### 2-1. 자동 (권장)
`alpha` 브랜치에 **서비스 코드**(`<svc>/**`, `shared-**/**`, `*.gradle.kts`)를 push/merge 하면:
1. `detect` — 변경된 서비스만 골라냄 (공통 모듈·gradle 변경 시 5개 전체).
2. `test` — 대상 서비스 테스트 실행. **여기서 실패하면 이하 단계가 진행되지 않음.**
3. `build-push` — arm64 이미지 빌드 → ghcr push (`:<sha>`, `:stg`).
4. `bump-manifest` — `overlays/stg` 이미지 태그를 커밋 SHA 로 갱신 커밋 → ArgoCD 자동 sync.

### 2-2. 수동
GitHub Actions → **release-stg** → *Run workflow* → `services` 입력(쉼표구분, 비우면 5개 전체).

### 2-3. 배포 결과 확인
```bash
kubectl -n apps rollout status deploy/<svc>
kubectl -n apps get pods -l app=<svc> -o wide
```

---

## 3. 롤백하기

배포한 버전이 문제일 때 두 가지 경로가 있습니다.

### 3-1. GitOps 롤백 (정석 — ArgoCD 가 진실의 원천)
`overlays/stg/kustomization.yaml` 의 이미지 태그를 **이전 SHA 로 되돌려 커밋**하면 ArgoCD 가 이전 버전으로 sync 합니다.
```bash
# 방법 A: 문제의 bump 커밋을 revert
git revert <문제의 ci(stg) bump 커밋>   # alpha 에
git push origin alpha

# 방법 B: 직접 이전 SHA 로 지정
cd docs/deploy/kustomize/overlays/stg
kustomize edit set image <svc>=ghcr.io/studieo-javry/<svc>:<이전-정상-SHA>
git commit -am "revert: <svc> to <이전-정상-SHA>" && git push origin alpha
```

### 3-2. 즉시 롤백 (긴급 — 클러스터에서 바로)
```bash
kubectl -n apps rollout undo deploy/<svc>          # 직전 ReplicaSet 으로
kubectl -n apps rollout undo deploy/<svc> --to-revision=<N>   # 특정 리비전으로
kubectl -n apps rollout history deploy/<svc>
```
> 주의: 3-2 는 클러스터 상태만 되돌립니다. ArgoCD selfHeal 이 켜져 있으면 다시 git 상태로 맞추므로,
> **긴급 완화 후에는 반드시 3-1(git) 로도 되돌려** 두 상태를 일치시킵니다.

---

## 4. 배포 특성과 리스크 (실측)

> 아래 수치는 실제 k8s(k3d)에서 **실 매니페스트와 동일한 `Recreate`+`replicas:1`+`readiness initialDelay:20s`** 로 재현·측정한 값입니다.
> 재현 방법은 §5.

### 4-1. 정상 배포에도 **다운타임이 있다** (Recreate + replicas 1)
`Recreate` 는 **기존 파드를 먼저 죽이고** 새 파드를 만듭니다. replicas 가 1이라 그 사이 **서비스가 비는 구간**이 생깁니다.
- **측정: 정상 배포 시 중단 ≈ 22s** (readiness `initialDelaySeconds:20s` + 파드 교체가 지배).
- 이벤트 근거: `Scaled down replica set ... 1 to 0` → `Scaled up ... 0 to 1`.
- ⚠️ **프로덕션은 여기에 JVM 콜드스타트가 더해집니다.** 매니페스트가 `startupProbe` 로 25~150s 를 예산으로 잡고 있어,
  **실제 정상 배포 다운타임은 대략 40s~3분** 범위가 됩니다.

### 4-2. 기동 실패 이미지를 배포하면 **전면 중단**된다
`Recreate` 는 이미 기존 파드를 죽였기 때문에, 새 이미지가 기동 실패(CrashLoop)하면 **롤백할 때까지 서비스가 완전히 죽어 있습니다.**
- **측정: 기동 실패 → Service endpoints 빈 상태(전면 중단)**, 파드 `BackOff`(CrashLoopBackOff).
- **롤백(`rollout undo`) 복구시간 ≈ 21s**, 배포~복구 **전면 중단 총 ≈ 42s**(+ 장애 감지 시간).

### 4-3. 테스트 게이트 (기동 실패를 사전 차단)
CI 에 `test` 잡을 추가해, **테스트 실패 시 이미지 push·배포 태그 갱신이 진행되지 않습니다.**
→ "기동 실패 이미지가 배포되는" 4-2 상황의 상당수를 **배포 이전에** 걸러냅니다.

### 4-4. 개선 여지 (선택)
- `RollingUpdate` + `replicas ≥ 2` 로 바꾸면 정상 배포 다운타임을 거의 0 으로 줄일 수 있음(단 리소스↑, 세션/마이그레이션 호환성 고려).
- readiness 를 엄격히 두면 새 파드가 Ready 되기 전엔 트래픽이 안 가므로 4-2 의 "잘못된 새 버전"도 옛 파드로 흡수(단 `Recreate`+replicas1 에서는 옛 파드가 이미 없어 효과 없음 → RollingUpdate 전제).

---

## 5. 이 수치를 재현하는 방법

`scripts/deploy-verify/` (실 매니페스트와 동일한 배포 특성의 스탠드인).

```bash
# 1) 최소 클러스터
k3d cluster create ev-deploy --servers 1 --agents 0 --no-lb --k3s-arg "--disable=traefik@server:0"
kubectl config use-context k3d-ev-deploy

# 2) 데모 앱(Recreate+replicas1+readiness20s) + 프로버(0.2s 간격 호출) 배포
kubectl apply -f scripts/deploy-verify/app.yaml -f scripts/deploy-verify/prober.yaml
kubectl rollout status deploy/demo

# 3) 측정 (정상배포 중단 / 기동실패 전면중단 / 롤백 복구)
bash scripts/deploy-verify/measure.sh

# 4) 정리
k3d cluster delete ev-deploy
```

- `app.yaml` — 실 core-api 와 같은 `Recreate`/`replicas:1`/`readiness initialDelay:20s`. 스탠드인 이미지(traefik/whoami)라 즉시 기동 → 측정값은 **전략+probe 하한**(프로덕션은 JVM 콜드스타트가 추가).
- `prober.yaml` — 0.2s 마다 서비스 호출해 OK/FAIL 로그. 이 로그의 FAIL 구간 = 중단창.
- `measure.sh` — A(정상배포 중단), B(기동실패→롤백 복구)를 순서대로 측정.

---

## 6. 운영 체크리스트

- [ ] 배포 전: 대상 서비스 테스트 그린 (CI `test` 잡이 자동 확인)
- [ ] 배포 중: `kubectl -n apps rollout status deploy/<svc>` 로 Ready 전환 확인
- [ ] 다운타임 인지: 정상 배포도 **수십 초~수 분** 중단(Recreate). 트래픽 많은 시간대 회피 권장
- [ ] 문제 시: §3-2 즉시 롤백 → §3-1 git 롤백으로 정합화
- [ ] 신환경: §1 시크릿·노드라벨·ArgoCD Application 준비

---

## 부록. 측정 근거(요약)
| 항목 | 값 | 근거 |
|---|---|---|
| 정상 배포 중단(스탠드인) | ≈ 22s | 프로버 FAIL 구간, 이벤트 Scaled 1→0→1 |
| 기동 실패 시 | 전면 중단(endpoints 빈 상태) | `kubectl get endpoints demo` 빈값, BackOff 이벤트 |
| 롤백 복구시간 | ≈ 21s | `rollout undo`~첫 OK |
| 전면 중단 총 | ≈ 42s | 기동실패 배포~복구 |
| 프로덕션 배포 다운타임(추정) | ≈ 40s~3분 | 위 + 매니페스트 startupProbe 예산 25~150s |
| 전체 테스트(alpha) | BUILD SUCCESSFUL | `./gradlew test --continue` 5개 서비스 그린 |
