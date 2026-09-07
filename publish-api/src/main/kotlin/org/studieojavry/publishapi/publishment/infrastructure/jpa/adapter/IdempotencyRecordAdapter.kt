package org.studieojavry.publishapi.publishment.infrastructure.jpa.adapter

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.studieojavry.publishapi.publishment.application.port.IdempotencyRecordPort
import org.studieojavry.publishapi.publishment.application.usecase.IdempotencyConflictException
import org.studieojavry.publishapi.publishment.infrastructure.jpa.PublishIdempotencyEntity
import org.studieojavry.publishapi.publishment.infrastructure.jpa.PublishIdempotencyJpaRepository
import java.time.Instant

@Repository
class IdempotencyRecordAdapter(
    private val jpa: PublishIdempotencyJpaRepository,
) : IdempotencyRecordPort {

    override fun find(idempotencyKey: String, userId: Long): IdempotencyRecordPort.Existing? =
        jpa.findByIdempotencyKeyAndUserId(idempotencyKey, userId)
            ?.let { IdempotencyRecordPort.Existing(requestHash = it.requestHash, slug = it.publishmentSlug) }

    override fun save(idempotencyKey: String, userId: Long, requestHash: String, slug: String) {
        val entity = PublishIdempotencyEntity(
            idempotencyKey = idempotencyKey,
            userId = userId,
            requestHash = requestHash,
            publishmentSlug = slug,
            createdAt = Instant.now(),
        )
        try {
            // saveAndFlush 로 unique(key,user) 위반을 이 호출에서 즉시 표면화.
            jpa.saveAndFlush(entity)
        } catch (e: DataIntegrityViolationException) {
            // 동시 중복 요청이 먼저 커밋 — 현재 tx 는 롤백되고, 클라이언트가 재시도하면 replay 된다.
            throw IdempotencyConflictException("duplicate publish request in progress — retry")
        }
    }

    override fun deleteBySlug(slug: String): Int = jpa.deleteByPublishmentSlug(slug)
}
