package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxEventJpaRepository
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxProperties
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxStatus
import java.time.Duration
import java.time.Instant

/**
 * Outbox 운영 액션 — 운영자가 `/internal/admin/outbox/...` 로 호출.
 *
 * 모든 액션은 *내부 호출자만* (gateway/iam 등 internal-auth 보유한 caller). 일반 사용자 API 아님.
 */
@Service
class OutboxAdminUseCases(
    private val outboxRepo: OutboxEventJpaRepository,
    private val properties: OutboxProperties,
) {
    private val log = KotlinLogging.logger {}

    /** 최근 [limit] 개 row, status 필터 옵션. */
    fun list(status: OutboxStatus?, limit: Int): List<OutboxEventEntity> =
        outboxRepo.findRecent(status?.name, limit.coerceIn(1, 500))

    /**
     * DEAD 또는 정체된 PENDING row 를 즉시 재시도 대기열로. attempts 0 으로 리셋하고 next_retry_at 을 now 로.
     * 이미 SENT 면 거부.
     */
    @Transactional
    fun retry(id: Long): OutboxEventEntity {
        val row = outboxRepo.findById(id).orElseThrow {
            NoSuchElementException("outbox row not found: $id")
        }
        if (row.status == OutboxStatus.SENT) {
            throw IllegalStateException("cannot retry SENT row: id=$id")
        }
        row.status = OutboxStatus.PENDING
        row.attempts = 0
        row.lastError = null
        row.nextRetryAt = Instant.now()
        log.info { "outbox admin: retry id=$id (was ${row.status})" }
        return outboxRepo.save(row)
    }

    @Transactional
    fun delete(id: Long) {
        if (!outboxRepo.existsById(id)) {
            throw NoSuchElementException("outbox row not found: $id")
        }
        outboxRepo.deleteById(id)
        log.info { "outbox admin: delete id=$id" }
    }

    /** SENT TTL 만료분 즉시 정리 — cron 대기 없이 운영자가 강제 트리거. */
    @Transactional
    fun cleanupSent(): Int {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.sentTtlDays))
        val deleted = outboxRepo.deleteSentBefore(cutoff)
        log.info { "outbox admin: cleanup-sent purged $deleted rows older than ${properties.sentTtlDays}d" }
        return deleted
    }
}
