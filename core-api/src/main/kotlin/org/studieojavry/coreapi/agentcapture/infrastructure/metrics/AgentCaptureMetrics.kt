package org.studieojavry.coreapi.agentcapture.infrastructure.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.AgentCaptureJpaRepository

/**
 * ARC(자동 문제해결 기록) 운영 지표 — Prometheus 로 수집해 Grafana 에서 본다.
 * `GithubSyncMetrics` 와 같은 방식(스크랩 시 DB 집계 게이지). 앱 계층을 건드리지 않아 결합이 없고,
 * 재시작에도 DB 기준이라 누락/리셋이 없다(인메모리 카운터 대비 장점).
 *
 * 메트릭: `agent.capture.count{status}` — 상태별 현재 건수.
 *  - status=OPEN            : 진행 중(미봉인)
 *  - status=READY_FOR_REVIEW: 봉인돼 사용자 검토 대기(백로그)
 *  - status=IMPORTED        : Case 로 반영됨(채택, 누적)
 *  - status=DISCARDED       : 사용자가 폐기(오생성 지표, 누적)
 *
 * Grafana PromQL 예:
 *  - 오생성률 = agent_capture_count{status="DISCARDED"} / (agent_capture_count{status="IMPORTED"} + agent_capture_count{status="DISCARDED"})
 *  - 채택률   = agent_capture_count{status="IMPORTED"}  / (agent_capture_count{status="IMPORTED"} + agent_capture_count{status="DISCARDED"})
 *  - 검토 백로그 = agent_capture_count{status="READY_FOR_REVIEW"}
 *
 * 자동 트리거 "성공률"(모델이 스스로 기록했는가)은 서버가 관측할 수 없다(모델 행동) — E2E 하네스
 * (e2e/arc-autorecord-e2e.sh)로 측정하며, 필요하면 Pushgateway 로 별도 게이지로 밀어 넣는다.
 */
@Component
class AgentCaptureMetrics(
    private val captures: AgentCaptureJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun registerGauges() {
        for (status in CaptureStatus.entries) {
            Gauge.builder(METRIC_COUNT) { captures.countByStatus(status).toDouble() }
                .tag("status", status.name)
                .description("자동 수집 초안 상태별 현재 건수(ARC 품질 모니터링)")
                .register(meterRegistry)
        }
    }

    companion object {
        const val METRIC_COUNT = "agent.capture.count"
    }
}
