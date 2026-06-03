package org.studieojavry.iamapi.auth.infrastructure.schedule

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.usecase.FinalizeDeletedAccountsUseCase

/**
 * PENDING_DELETION grace period 만료 사용자 finalize 배치.
 * 기본 1시간 간격(개발 편의), 첫 실행은 부팅 5분 후. 운영에선 ShedLock 으로 다중 인스턴스 안전성 추가 권장.
 */
@Component
class AccountDeletionScheduler(
    private val finalize: FinalizeDeletedAccountsUseCase,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    fun runFinalize() {
        runCatching { finalize.invoke() }
            .onFailure { log.warn(it) { "[account-finalize-scheduler] batch failed" } }
    }
}
