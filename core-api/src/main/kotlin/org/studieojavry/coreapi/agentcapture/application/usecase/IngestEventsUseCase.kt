package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.dao.DataIntegrityViolationException
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
 * (connectionId, clientEventId) UNIQUE 로 멱등 — 동시 중복 삽입 경합은 DUPLICATE 로 수렴.
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

        events.findByConnectionAndClientId(connectionId, item.clientEventId)?.let { existing ->
            return if (existing.payloadHash == hash) {
                EventOutcome(item.clientEventId, EventResultType.DUPLICATE)
            } else {
                EventOutcome(item.clientEventId, EventResultType.REJECTED, "clientEventId conflict (different payload)")
            }
        }

        return try {
            events.save(
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
            EventOutcome(item.clientEventId, EventResultType.ACCEPTED)
        } catch (ex: DataIntegrityViolationException) {
            // 동시 삽입 경합(UNIQUE 위반) → 재전송처럼 멱등 수렴.
            EventOutcome(item.clientEventId, EventResultType.DUPLICATE)
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
