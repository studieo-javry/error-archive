package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventItem
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListAgentEventsUseCase
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentEventListResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentEventSummaryResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.EventBatchRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.EventBatchResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.EventResultResponse
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Hook 자동수집 이벤트 수신(이슈 #145 §4). 커넥터 로컬 큐가 정규화한 CaptureEvent 를 배치로 보낸다.
 * 인증·배치 외형 오류는 요청 전체 거절(4xx), 개별 이벤트 유효성은 해당 이벤트 결과(REJECTED)로 반환.
 * 전체 HTTP 성공만 보고 모든 이벤트를 ACK 처리하지 않도록, 이벤트별 결과를 돌려준다.
 */
@Tag(name = "agent-event", description = "Hook 이벤트 배치 적재(case:read 아님 — capture:write 필요).")
@RestController
@RequestMapping("/api/v1/agent/events")
class AgentEventController(
    private val ingestEvents: IngestEventsUseCase,
    private val listEvents: ListAgentEventsUseCase,
    private val objectMapper: ObjectMapper,
) {

    @Operation(
        summary = "미분류 이벤트 조회",
        description = "본인 소유 이벤트를 상태별 조회(기본 UNCLASSIFIED). AI 가 어떤 이벤트를 Capture 에 연결할지 " +
            "판단하기 위한 후보. sessionId 로 현재 세션만 좁힐 수 있다. 조회는 연결하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "403", description = "capture:read 스코프 없음"),
    )
    @GetMapping
    fun list(
        principal: AgentPrincipal,
        @RequestParam(required = false, defaultValue = "UNCLASSIFIED") status: String,
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): AgentEventListResponse {
        principal.requireScope(AgentScopes.CAPTURE_READ)
        val st = runCatching { CaptureEventStatus.valueOf(status.uppercase()) }.getOrDefault(CaptureEventStatus.UNCLASSIFIED)
        val items = listEvents.invoke(principal.userId, st, sessionId?.takeIf { it.isNotBlank() }, limit.coerceIn(1, 100))
        return AgentEventListResponse(items.map(::toSummary))
    }

    private fun toSummary(e: CaptureEvent): AgentEventSummaryResponse = AgentEventSummaryResponse(
        clientEventId = e.clientEventId,
        eventType = e.eventType,
        sessionId = e.sessionId,
        observedAt = e.observedAt,
        toolCallId = e.toolCallId,
        truncated = e.truncated,
        status = e.status.name,
        redactedPayload = objectMapper.readTree(e.redactedPayload),
    )

    @Operation(
        summary = "이벤트 배치 적재",
        description = "최대 100건·본문 ≤1 MiB·이벤트당 payload ≤64 KiB. ACCEPTED 는 적재일 뿐 Step 구조화가 아니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "이벤트별 ACCEPTED/DUPLICATE/REJECTED"),
        ApiResponse(responseCode = "400", description = "빈 배치/100건 초과/본문 1 MiB 초과 등 배치 외형 오류"),
        ApiResponse(responseCode = "403", description = "capture:write 스코프 없음"),
    )
    @PostMapping("/batch")
    fun batch(
        principal: AgentPrincipal,
        @Valid @RequestBody request: EventBatchRequest,
    ): EventBatchResponse {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        require(request.events.isNotEmpty()) { "events must not be empty" }
        require(request.events.size <= MAX_BATCH) { "batch exceeds $MAX_BATCH events" }

        val items = request.events.map { e ->
            IngestEventItem(
                clientEventId = e.clientEventId,
                eventType = e.eventType,
                localSequence = e.localSequence,
                observedAt = e.observedAt,
                redactedPayloadJson = objectMapper.writeValueAsString(e.redactedPayload),
                projectBindingId = e.projectBindingId,
                clientKind = e.clientKind,
                clientVersion = e.clientVersion,
                sessionId = e.sessionId,
                streamId = e.streamId,
                toolCallId = e.toolCallId,
                truncated = e.truncated,
                evidenceRefs = e.evidenceRefs,
            )
        }
        require(items.sumOf { it.redactedPayloadJson.toByteArray(Charsets.UTF_8).size } <= MAX_BATCH_BYTES) {
            "batch body exceeds 1 MiB"
        }

        val outcomes = ingestEvents.invoke(UUID.fromString(principal.connectionId), principal.userId, items)
        return EventBatchResponse(outcomes.map { EventResultResponse(it.clientEventId, it.result.name, it.reason) })
    }

    private companion object {
        const val MAX_BATCH = 100
        const val MAX_BATCH_BYTES = 1024 * 1024
    }
}
