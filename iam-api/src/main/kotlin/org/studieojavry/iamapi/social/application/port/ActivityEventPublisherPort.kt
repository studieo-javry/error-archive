package org.studieojavry.iamapi.social.application.port

import java.time.Instant

/**
 * 사용자 활동 이벤트 (잔디용) 발행 — iam-api 의 social 도메인용.
 *
 * iam-api 가 발행하는 type 은 현재 `FOLLOWED_USER` 한 가지. insight-api 의 ActivityType 과 같은 문자열로 매핑.
 *
 * fire-and-forget 의미를 *transactional outbox* 가 보강: publish() 는 *outbox INSERT* 만 한다 →
 * 도메인 트랜잭션 commit 시 영속 → 별도 relayer 가 비동기로 Kafka 송신 + 실패 시 재시도.
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
        FOLLOWED_USER,
    }
}
