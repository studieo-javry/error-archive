package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Hook 자동수집 이벤트(이슈 #145). 커넥터가 정규화해 배치로 보낸 실행 관측 1건.
 *
 *  - clientEventId: 최초 로컬 저장 시 1회 생성, 재전송에서 유지 → (connectionId, clientEventId) 로 멱등.
 *  - payloadHash: 같은 clientEventId 에 다른 payload 가 오면 충돌(REJECTED)로 거른다.
 *  - status UNCLASSIFIED: 적재만 된 상태. 개발 AI 가 MCP 로 Capture 에 연결하면 LINKED(후속 증분).
 *  - observedAt(클라 관측) 과 receivedAt(서버 수신) 을 구분한다.
 */
data class CaptureEvent(
    val id: Long? = null,
    val connectionId: UUID,
    val ownerUserId: Long,
    val projectBindingId: Long? = null,
    val clientEventId: String,
    val clientKind: String? = null,
    val clientVersion: String? = null,
    val sessionId: String? = null,
    val streamId: String? = null,
    val toolCallId: String? = null,
    val eventType: String,
    val localSequence: Long,
    val observedAt: Instant,
    val receivedAt: Instant,
    /** 마스킹된 payload(직렬화 JSON, jsonb 저장). */
    val redactedPayload: String,
    val truncated: Boolean = false,
    val evidenceRefs: List<String> = emptyList(),
    val payloadHash: String,
    val captureId: Long? = null,
    val status: CaptureEventStatus = CaptureEventStatus.UNCLASSIFIED,
)

enum class CaptureEventStatus { UNCLASSIFIED, LINKED, DISCARDED }
