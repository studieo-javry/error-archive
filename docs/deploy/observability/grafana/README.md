# Grafana 알림 provisioning (LLM 관측)

DB VM(`10.0.0.183`)의 observability 스택(docker-compose: Prometheus·Loki·Grafana·Alloy) 중
**Grafana 알림**을 코드로 기록한 사본이다. 실물은 DB VM `/home/ubuntu/observability/grafana/provisioning/` 에 있고,
여기는 **비밀값(Slack webhook)을 가린 버전관리 사본**이다(재현·리뷰·문서화용).

## 구성
```
provisioning/
  datasources/prometheus.yml     # Prometheus datasource + uid: prometheus (알림 룰이 참조)
  alerting/
    contactpoints.yaml           # Slack contact point (webhook 은 <<SLACK_WEBHOOK_URL>> 자리표시자)
    policies.yaml                # 알림 그룹핑/타이밍(루트 정책)
    rules.yaml                   # LLM 알림 룰 4개 (룰별 receiver: slack-ea 직접 지정)
```

## 알림 룰 (core-api LLM 메트릭 기반)
| uid | 조건 | severity |
|---|---|---|
| `llm_cb_open` | CircuitBreaker("llm") OPEN 2분 지속 | warning |
| `llm_fail_rate` | 호출 실패율 >50% (최근 10분) | warning |
| `llm_monthly_cost` | 30일 추정비용 >$16 (콘솔 월 한도 $20의 80%) | warning |
| `llm_daily_calls` | 24h 호출 >240 (전역 캡 300의 80%) | info |

- 전제 메트릭: `resilience4j_circuitbreaker_state{name="llm"}`, `llm_draft_api_calls_total`, `llm_draft_estimated_cost_usd_total` (core-api `MeteredAnthropicCaller`/`ResilienceConfig`).
- 라우팅: Grafana 11 `alertingSimplifiedRouting` 로 **룰별 `receiver: slack-ea`** 직접 지정(정책 트리 대신 — 더 확실).
- `noDataState: OK` — 메트릭이 없을 때(무트래픽) 오발화하지 않음.

## 설치 / 갱신 (DB VM)
1. 이 디렉터리를 `/home/ubuntu/observability/grafana/provisioning/` 로 복사.
2. `alerting/contactpoints.yaml` 의 `<<SLACK_WEBHOOK_URL>>` 를 **실제 Slack Incoming Webhook URL** 로 교체(이 파일만, git 밖).
3. Grafana 재시작: `cd /home/ubuntu/observability && docker compose restart grafana`
4. 로그 확인: `docker logs obs-grafana | grep "provision alerting"` 에 에러 없이 `finished` 면 성공.
5. (선택) self-test: 항상 발화하는 임시 룰을 넣어 Slack 수신 확인 후 제거.

## 주의
- `datasources/prometheus.yml` 의 `uid: prometheus` 는 알림 룰이 datasource 를 참조하기 위한 고정 uid.
  기존 대시보드가 **기본 datasource(isDefault)** 를 쓰면 영향 없지만, 특정 uid 를 하드코딩했다면 패널에서 datasource 재선택 필요.
- Slack 발송 성공은 Grafana 로그에 잘 안 남으므로 **수신 확인**이 가장 정확(실패 시 `Notify for alerts failed` 로그).
- 알림 '발송'은 Alertmanager 가 아니라 **Grafana 자체 알림**으로 처리(이 스택엔 Alertmanager 미배포).
