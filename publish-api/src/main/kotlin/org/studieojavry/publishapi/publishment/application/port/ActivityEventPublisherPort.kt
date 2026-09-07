package org.studieojavry.publishapi.publishment.application.port

import java.time.Instant

/**
 * 사용자 활동 이벤트 (잔디용) 발행 — publish-api 도메인용.
 *
 * 현재 type 은 `CASE_PUBLISHED` 한 가지. transactional outbox + relayer 가 fire-and-forget 보강.
 */
interface ActivityEventPublisherPort {

    fun publish(event: ActivityEvent)

    data class ActivityEvent(
        val userId: Long,
        val type: Type,
        val occurredAt: Instant,
        val idempotencyKey: String,
        val meta: Map<String, Any?>? = null,
    )

    enum class Type {
        CASE_PUBLISHED,
    }
}
