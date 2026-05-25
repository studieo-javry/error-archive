package org.studieojavry.iamapi.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 회원 탈퇴 finalize 배치용 인프라:
 *  - `TransactionTemplate` — `FinalizeDeletedAccountsUseCase` 가 사용자별 독립 트랜잭션을 열기 위해 사용
 *    (`@Transactional` self-invocation 함정 회피).
 *  - `@EnableScheduling` — `AccountDeletionScheduler.@Scheduled` 활성화.
 */
@Configuration
@EnableScheduling
class AccountDeletionConfig {

    @Bean
    fun accountDeletionTxTemplate(txManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(txManager)
}
