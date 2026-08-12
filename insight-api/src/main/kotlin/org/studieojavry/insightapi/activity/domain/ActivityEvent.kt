package org.studieojavry.insightapi.activity.domain

import java.time.Instant

/**
 * 출처 서비스가 publish 한 사용자 활동 1건의 진실의 원천.
 *
 * `idempotencyKey` 가 UNIQUE — at-least-once Kafka 의 중복 수신을 DB 한 줄로 흡수.
 * raw 보존 기간 13개월 (12개월 잔디 + 월말 갱신용 +1).
 *
 * 가중치 정책이 바뀌면 본 raw 를 다시 읽어 `insight_activity_daily` 를 truncate-and-rebuild 한다.
 */
class ActivityEvent private constructor(
    val id: Long?,
    val userId: Long,
    val type: ActivityType,
    val occurredAt: Instant,
    val score: Int,
    val idempotencyKey: String,
    val metaJson: String?,
    val createdAt: Instant,
) {
    companion object {
        const val IDEMPOTENCY_KEY_MAX = 128
        fun create(
            userId: Long,
            type: ActivityType,
            occurredAt: Instant,
            score: Int,
            idempotencyKey: String,
            metaJson: String? = null,
        ): ActivityEvent {
            require(score >= 0) { "score must be >= 0 (got $score)" }
            require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
            require(idempotencyKey.length <= IDEMPOTENCY_KEY_MAX) {
                "idempotencyKey length <= $IDEMPOTENCY_KEY_MAX (got ${idempotencyKey.length})"
            }
            return ActivityEvent(null, userId, type, occurredAt, score, idempotencyKey, metaJson, Instant.now())
        }

        fun rehydrate(
            id: Long, userId: Long, type: ActivityType, occurredAt: Instant,
            score: Int, idempotencyKey: String, metaJson: String?, createdAt: Instant,
        ) = ActivityEvent(id, userId, type, occurredAt, score, idempotencyKey, metaJson, createdAt)
    }
}
