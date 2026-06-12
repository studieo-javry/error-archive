package org.studieojavry.insightapi.activity.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.insightapi.activity.application.usecase.IngestActivityEventUseCase
import org.studieojavry.insightapi.activity.domain.ActivityType
import org.studieojavry.insightapi.activity.infrastructure.dlq.ActivityEventDlqEntity
import org.studieojavry.insightapi.activity.infrastructure.dlq.ActivityEventDlqRepository
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqFailureKind
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqStatus
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * `user-activity.v1` topic consumer. core-api / iam-api / publish-api 가 outbox + relayer 로 publish.
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
 *
 * **실패 처리 (DLQ)** — 모든 실패는 `insight_activity_event_dlq` 로 격리:
 *  - **Parse 실패** (JSON 깨짐 / 필수 필드 누락 / unknown ActivityType / Instant.parse 실패):
 *    → `failureKind=PARSE, status=DEAD` 즉시 저장. 자동 retry 안 함 (같은 결과 보장됨).
 *  - **Ingest 실패** (DB lock / transient infra error):
 *    → `failureKind=INGEST, status=PENDING` 저장. retry 잡이 backoff 으로 재시도.
 *  - Kafka offset 은 항상 commit — broker 가 같은 메시지 재전송 안 함. 책임은 DLQ.
 *
 * 정상 처리 + 멱등 무시(duplicate idempotencyKey)는 ingestUseCase 안에서 silent.
 */
@Component
class ActivityEventConsumer(
    private val ingestUseCase: IngestActivityEventUseCase,
    private val objectMapper: ObjectMapper,
    private val dlqRepo: ActivityEventDlqRepository,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = [TOPIC], groupId = "insight-api")
    fun onEvent(value: String) {
        // 1) JSON parse
        val msg: ActivityMessage = try {
            objectMapper.readValue(value, ActivityMessage::class.java)
        } catch (ex: Exception) {
            saveDeadParse(value, key = null, error = "json: ${ex.message ?: ex.javaClass.simpleName}")
            log.warn(ex) { "activity DLQ PARSE (json): ${value.take(200)}" }
            return
        }

        // 2) occurredAt parse
        val occurredAtInstant: Instant = try {
            Instant.parse(msg.occurredAt)
        } catch (ex: Exception) {
            saveDeadParse(value, msg.idempotencyKey, error = "occurredAt: ${ex.message ?: ex.javaClass.simpleName}")
            log.warn(ex) { "activity DLQ PARSE (occurredAt): key=${msg.idempotencyKey}" }
            return
        }

        // 3) ActivityType 검증
        if (ActivityType.fromCodeOrNull(msg.type) == null) {
            saveDeadParse(value, msg.idempotencyKey, error = "unknown ActivityType: ${msg.type}")
            log.warn { "activity DLQ PARSE (unknown type): type=${msg.type} key=${msg.idempotencyKey}" }
            return
        }

        // 4) Ingest — 실패 시 INGEST PENDING (retry 잡이 처리)
        try {
            val metaJson = msg.meta?.let { objectMapper.writeValueAsString(it) }
            ingestUseCase.invoke(IngestActivityEventUseCase.Input(
                userId = msg.userId,
                typeCode = msg.type,
                occurredAt = occurredAtInstant,
                score = msg.score ?: 0,
                idempotencyKey = msg.idempotencyKey,
                metaJson = metaJson,
            ))
        } catch (ex: Exception) {
            dlqRepo.save(ActivityEventDlqEntity(
                rawPayload = value,
                idempotencyKey = msg.idempotencyKey,
                failureKind = DlqFailureKind.INGEST,
                lastError = "ingest: ${ex.message ?: ex.javaClass.simpleName}".take(2000),
                attempts = 1,
            ))
            log.warn(ex) { "activity DLQ INGEST: key=${msg.idempotencyKey}" }
        }
    }

    private fun saveDeadParse(payload: String, key: String?, error: String) {
        dlqRepo.save(ActivityEventDlqEntity(
            rawPayload = payload,
            idempotencyKey = key,
            failureKind = DlqFailureKind.PARSE,
            lastError = error.take(2000),
            status = DlqStatus.DEAD,
            attempts = 1,
        ))
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
