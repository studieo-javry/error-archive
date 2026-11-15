package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Hook 자동수집 이벤트 배치 적재(이슈 #145 §4).
 *
 * 이벤트별 결과: ACCEPTED(영속 저장) / DUPLICATE(같은 clientEventId·같은 payload 재전송) /
 * REJECTED(같은 clientEventId·다른 payload 충돌, payload 과대 등). ACCEPTED 는 적재일 뿐 Step 구조화 아님.
 * (connectionId, clientEventId) UNIQUE 로 멱등. 삽입은 INSERT … ON CONFLICT DO NOTHING(insertIfAbsent)로
 * 하여 동시 중복 삽입 경합에서도 예외/트랜잭션 오염 없이 DUPLICATE 로 수렴한다(#180 권고).
 */
@Service
class IngestEventsUseCase(
    private val events: CaptureEventRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(connectionId: UUID, ownerUserId: Long, items: List<IngestEventItem>): List<EventOutcome> {
        val now = Instant.now(clock)
        return items.map { item -> ingestOne(connectionId, ownerUserId, item, now) }
    }

    private fun ingestOne(connectionId: UUID, ownerUserId: Long, item: IngestEventItem, now: Instant): EventOutcome {
        if (item.redactedPayloadJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return EventOutcome(item.clientEventId, EventResultType.REJECTED, "redactedPayload exceeds 64 KiB")
        }
        val hash = sha256(item.redactedPayloadJson)

        // 충돌 없는 멱등 삽입(ON CONFLICT DO NOTHING) — 동시 중복 삽입에도 예외를 던지지 않아
        // 트랜잭션이 abort/rollback-only 로 오염되지 않는다(같은 배치의 다른 항목·commit 보존). #180 권고 반영.
        val inserted = events.insertIfAbsent(
            CaptureEvent(
                connectionId = connectionId,
                ownerUserId = ownerUserId,
                projectBindingId = item.projectBindingId,
                clientEventId = item.clientEventId,
                clientKind = item.clientKind,
                clientVersion = item.clientVersion,
                sessionId = item.sessionId,
                streamId = item.streamId,
                toolCallId = item.toolCallId,
                eventType = item.eventType,
                localSequence = item.localSequence,
                observedAt = item.observedAt,
                receivedAt = now,
                redactedPayload = item.redactedPayloadJson,
                truncated = item.truncated,
                evidenceRefs = item.evidenceRefs,
                payloadHash = hash,
                captureId = null,
                status = CaptureEventStatus.UNCLASSIFIED,
            ),
        )
        if (inserted) return EventOutcome(item.clientEventId, EventResultType.ACCEPTED)

        // 충돌(이미 존재) → 재조회로 분류. 같은 payload=DUPLICATE, 다른 payload=REJECTED.
        // 동시 경합으로 승자 행이 아직 안 보이면(미커밋) 안전 기본값 DUPLICATE(멱등 — 재전송처럼 수렴).
        val existing = events.findByConnectionAndClientId(connectionId, item.clientEventId)
        return if (existing == null || existing.payloadHash == hash) {
            EventOutcome(item.clientEventId, EventResultType.DUPLICATE)
        } else {
            EventOutcome(item.clientEventId, EventResultType.REJECTED, "clientEventId conflict (different payload)")
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}

data class IngestEventItem(
    val clientEventId: String,
    val eventType: String,
    val localSequence: Long,
    val observedAt: Instant,
    val redactedPayloadJson: String,
    val projectBindingId: Long? = null,
    val clientKind: String? = null,
    val clientVersion: String? = null,
    val sessionId: String? = null,
    val streamId: String? = null,
    val toolCallId: String? = null,
    val truncated: Boolean = false,
    val evidenceRefs: List<String> = emptyList(),
)

enum class EventResultType { ACCEPTED, DUPLICATE, REJECTED }

data class EventOutcome(val clientEventId: String, val result: EventResultType, val reason: String? = null)

/**
 * 본인 소유 이벤트를 상태별 조회(B) — AI 가 어떤 이벤트를 Capture 에 연결할지 판단하기 위한 후보.
 * 기본 status=UNCLASSIFIED, 선택 sessionId 필터. 연결은 하지 않는다(판단·연결은 호출측 AI + 링크 API).
 */
@Service
class ListAgentEventsUseCase(
    private val events: CaptureEventRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long, status: CaptureEventStatus, sessionId: String?, limit: Int): List<CaptureEvent> =
        events.findByOwnerAndStatus(ownerUserId, status, sessionId, limit.coerceIn(1, MAX_LIMIT))

    private companion object {
        const val MAX_LIMIT = 100
    }
}
