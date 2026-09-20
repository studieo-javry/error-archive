# 배포 재현·실패 동작 검증 기록 (Item 1~4)

- **일시**: 2026-09-20 · 대상 `origin/alpha`(워크트리 `verify/event-resilience`)
- **범위**: 배포 파이프라인 품질 게이트 + Recreate 중단/롤백 실측 + 런북 문서화

## Item 1 — 테스트 게이트 연결 (완료)
- 조사: `.github/workflows/release-stg.yml` 하나뿐, 파이프라인에 `test` 호출 **전무**(bootJar만).
- 사전확인: `./gradlew test --continue` → **BUILD SUCCESSFUL**, 5개 서비스(`:core/iam/noti/insight/publish:test`) 전부 그린 → 게이트 추가해도 배포 안 막힘.
- 변경: `release-stg.yml` 에 `test` 잡 추가(matrix=대상 서비스, `./gradlew :<svc>:test`), `build-push.needs: [detect, test]` 로 게이트.
  - 체인: `detect → test → build-push → bump-manifest`. 테스트 실패 시 이미지 push·overlay 태그 갱신이 진행되지 않음.
  - composite build 라 `:<svc>:test` 가 각 included build 로 위임됨(검증 완료).

## Item 2 — Recreate 정상 배포 중단 실측 (완료)
- 환경: k3d 단일노드 `ev-deploy`, 실 매니페스트와 동일한 `Recreate`+`replicas:1`+`readiness initialDelay:20s`(스탠드인 traefik/whoami) + 0.2s 프로버.
- 결과: 정상 배포(rev 변경) 시 **중단창 ≈ 22s**. 이벤트 `Scaled down 1→0` → `Scaled up 0→1` 로 Recreate 확인.
- 해석: 스탠드인은 즉시 기동이라 이 22s 는 **전략+probe 하한**. 프로덕션은 JVM 콜드스타트(매니페스트 startupProbe 예산 25~150s)가 더해져 **실 다운타임 ≈ 40s~3분** 추정.

## Item 3 — 기동 실패 배포 → 전면 중단 → 롤백 복구 실측 (완료)
- 주입: 기동 실패 이미지(busybox `exit 1` → CrashLoop) 배포.
- 관측: 파드 `BackOff`(CrashLoopBackOff), `kubectl get endpoints demo` **빈값 → 전면 중단**(Recreate 라 기존 파드 이미 제거됨).
- 복구: `kubectl rollout undo` → **롤백 복구시간 ≈ 21s**, 배포~복구 **전면 중단 총 ≈ 42s**.
- 실패지표: 프로버 표본 FAIL 38 / OK 28(구간). endpoints 빈 구간 = 중단.

## Item 4 — 런북 문서화 (완료)
- `docs/deploy-runbook.md`: 신환경 준비(네임스페이스·노드라벨·시크릿·ArgoCD Application·첫 sync) → 배포(자동/수동/게이트) → 롤백(GitOps revert / kubectl undo) → 배포 특성(위 실측) → 재현법 → 체크리스트.
- 신환경 준비 근거: 서비스별 `secretRef: <svc>-secrets`(git 밖 kubectl 생성), `configMapRef: <svc>-env`(overlay 생성), ns `apps`, `nodeSelector role=apps`. ArgoCD Application 은 리포 밖 설정(overlays/stg 를 바라봄).

## 산출물 (working-tree only, git 미커밋)
- `.github/workflows/release-stg.yml` — test 게이트 추가 (※ 이건 코드/CI 변경이라 커밋 대상이 될 수 있음 — 사용자 승인 후)
- `docs/deploy-runbook.md` — 운영 런북
- `docs/record/verify-deploy-resilience-2026-09-20.md` — 본 기록
- `scripts/deploy-verify/` — `app.yaml`·`prober.yaml`·`measure.sh` (재현 하니스)

## 남은 개선(선택)
- 다운타임 축소: `RollingUpdate`+`replicas≥2` 전환 검토(리소스·마이그레이션 호환성 트레이드오프).
- 게이트 세분화: 현재 한 서비스 테스트 실패 시 전체 배포 중단(안전측). 서비스별 독립 배포가 필요하면 잡 구조 조정.
