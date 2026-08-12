package org.studieojavry.iamapi.auth.infrastructure.schedule

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.usecase.FinalizeDeletedAccountsUseCase

/**
 * PENDING_DELETION grace period 만료 사용자 finalize 배치.
 * 기본 1시간 간격, 첫 실행은 부팅 5분 후.
 * 다중 인스턴스 안전성: ShedLock 으로 한 번에 한 인스턴스만 실행 (M1). ShedLock 인프라는 SchedulingConfig.
 */
@Component
class AccountDeletionScheduler(
    private val finalize: FinalizeDeletedAccountsUseCase,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "iam-account-finalize", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun runFinalize() {
        runCatching { finalize.invoke() }
            .onFailure { log.warn(it) { "[account-finalize-scheduler] batch failed" } }
    }
}
