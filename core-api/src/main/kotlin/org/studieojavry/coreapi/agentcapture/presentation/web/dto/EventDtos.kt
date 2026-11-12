package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import tools.jackson.databind.JsonNode
import java.time.Instant

@Schema(description = "Hook 자동수집 이벤트 배치(최대 100건, 본문 ≤1 MiB).")
data class EventBatchRequest(
    @field:Valid
    val events: List<EventItemRequest>,
)

@Schema(description = "정규화한 CaptureEvent 1건.")
data class EventItemRequest(
    @field:NotBlank
    @Schema(description = "최초 로컬 저장 시 1회 생성, 재전송에서 유지. (connectionId,clientEventId) 로 멱등.")
    val clientEventId: String,
    @field:NotBlank
    val eventType: String,
    val localSequence: Long,
    @Schema(description = "클라이언트 관측 시각(ISO-8601). 서버 수신 시각과 구분 저장.")
    val observedAt: Instant,
    @Schema(description = "마스킹된 payload(≤64 KiB). 더 큰 근거는 첨부로.")
    val redactedPayload: JsonNode,
    val projectBindingId: Long? = null,
    val clientKind: String? = null,
    val clientVersion: String? = null,
    val sessionId: String? = null,
    val streamId: String? = null,
    val toolCallId: String? = null,
    val truncated: Boolean = false,
    val evidenceRefs: List<String> = emptyList(),
)

@Schema(description = "이벤트별 적재 결과.")
data class EventResultResponse(
    val clientEventId: String,
    @Schema(description = "ACCEPTED(저장) | DUPLICATE(재전송) | REJECTED(충돌·과대 등)")
    val result: String,
    val reason: String? = null,
)

@Schema(description = "배치 적재 결과. HTTP 200 이어도 개별 이벤트는 REJECTED 일 수 있다.")
data class EventBatchResponse(
    val results: List<EventResultResponse>,
)

@Schema(description = "미분류 이벤트를 OPEN Capture 에 연결하는 요청.")
data class LinkEventsRequest(
    @field:jakarta.validation.constraints.NotEmpty
    val clientEventIds: List<String>,
)

@Schema(description = "연결 결과. linked=연결됨(멱등 포함), skipped=없음/타 Capture 소속이라 가로채지 않음.")
data class LinkEventsResponse(
    val linked: List<String>,
    val skipped: List<String>,
)
