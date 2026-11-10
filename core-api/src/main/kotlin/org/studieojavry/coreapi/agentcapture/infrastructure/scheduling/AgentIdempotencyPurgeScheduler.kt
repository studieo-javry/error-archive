package org.studieojavry.coreapi.agentcapture.infrastructure.scheduling

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.agentcapture.application.usecase.PurgeAgentIdempotencyReceiptsUseCase

/**
 * 에이전트 멱등 영수증 30일 보존 정리 트리거(스케줄·락=인프라, 정책=use case).
 * 기본 매일 03:30. 다중 인스턴스 중 한 곳에서만 실행(@SchedulerLock).
 */
@Component
class AgentIdempotencyPurgeScheduler(
    private val purge: PurgeAgentIdempotencyReceiptsUseCase,
) {

    @Scheduled(cron = "\${core.agentcapture.idempotency-purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "purgeAgentIdempotencyReceipts", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    fun purge() {
        purge.invoke()
    }
}
