package org.studieojavry.insightapi.activity.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.insightapi.activity.application.usecase.IngestActivityEventUseCase
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * `user-activity.v1` topic consumer. core-api / iam-api 가 *fire-and-forget* publish.
 *
 * 메시지 스키마 ({} 는 nullable):
 *   {
 *     "userId": Long,
 *     "type":   "CASE_CREATED" | ...,
 *     "occurredAt": ISO-8601 string,
 *     "score":  Int(optional, 0 이면 server 가 yml 값 사용),
 *     "idempotencyKey": "case:42",
 *     "meta": {...} (optional, JSON object)
 *   }
 */
@Component
class ActivityEventConsumer(
    private val ingestUseCase: IngestActivityEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = [TOPIC], groupId = "insight-api")
    fun onEvent(value: String) {
        try {
            val msg = objectMapper.readValue(value, ActivityMessage::class.java)
            val metaJson = msg.meta?.let { objectMapper.writeValueAsString(it) }
            ingestUseCase.invoke(IngestActivityEventUseCase.Input(
                userId = msg.userId,
                typeCode = msg.type,
                occurredAt = Instant.parse(msg.occurredAt),
                score = msg.score ?: 0,
                idempotencyKey = msg.idempotencyKey,
                metaJson = metaJson,
            ))
        } catch (e: Exception) {
            // 단발성 파싱 실패는 swallow + log. broker 가 같은 메시지를 재전송 안 함 (auto-commit).
            // 추후 DLQ 도입 시 throw 로 변경.
            log.error(e) { "failed to ingest activity event: $value" }
        }
    }

    data class ActivityMessage(
        val userId: Long,
        val type: String,
        val occurredAt: String,
        val score: Int? = null,
        val idempotencyKey: String,
        val meta: Map<String, Any?>? = null,
    )

    companion object {
        const val TOPIC = "user-activity.v1"
    }
}
