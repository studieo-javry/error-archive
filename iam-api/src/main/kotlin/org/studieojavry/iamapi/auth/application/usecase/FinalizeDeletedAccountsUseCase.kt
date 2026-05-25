package org.studieojavry.iamapi.auth.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.config.AccountDeletionProperties
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import java.time.Instant

/**
 * PENDING_DELETION 사용자 중 grace period(기본 30일) 만료된 사용자를 **DELETED 로 확정**.
 *  - User.finalizeDeletion() → status=DELETED + PII 익명화(email/displayName/avatar/bio).
 *  - OAuth identity 삭제 → 같은 GitHub 계정으로 추후 재가입 가능.
 *
 * 멱등: 같은 사용자에 두 번 호출돼도 두 번째는 no-op(domain finalizeDeletion 이 status 검사).
 * 각 사용자 처리를 **TransactionTemplate** 으로 독립 트랜잭션 — 한 건 실패가 다른 건 롤백시키지 않는다.
 * (`@Transactional` self-invocation 함정 회피)
 */
@Service
class FinalizeDeletedAccountsUseCase(
    private val userRepository: UserRepositoryPort,
    private val socialIdentities: SocialIdentityRepositoryPort,
    private val properties: AccountDeletionProperties,
    private val txTemplate: TransactionTemplate,
) {
    private val log = KotlinLogging.logger {}

    /** @return 확정 처리한 사용자 수. */
    fun invoke(): Int {
        val threshold = Instant.now().minus(properties.gracePeriod)
        val batchSize = properties.batchSize
        var total = 0

        repeat(MAX_BATCHES) {
            val batch = userRepository.findPendingDeletionBefore(threshold, batchSize)
            if (batch.isEmpty()) return total.also { logIfAny(it) }
            var done = 0
            for (user in batch) {
                runCatching { finalizeOne(user.id!!) }
                    .onSuccess { done++ }
                    .onFailure { log.warn(it) { "[account-finalize] failed userId=${user.id}" } }
            }
            total += done
            if (done == 0 || batch.size < batchSize) return total.also { logIfAny(it) }
        }
        logIfAny(total)
        return total
    }

    private fun finalizeOne(userId: Long) {
        txTemplate.execute {
            val user = userRepository.findById(userId) ?: return@execute
            if (user.status == UserStatus.DELETED) return@execute
            user.finalizeDeletion()
            userRepository.save(user)
            socialIdentities.deleteByUserId(userId)
            log.info { "[account-finalize] user=$userId → DELETED + PII anonymized + OAuth identity removed" }
        }
    }

    private fun logIfAny(count: Int) {
        if (count > 0) log.info { "[account-finalize] finalized $count accounts (grace=${properties.gracePeriod})" }
    }

    companion object {
        private const val MAX_BATCHES = 1000
    }
}
