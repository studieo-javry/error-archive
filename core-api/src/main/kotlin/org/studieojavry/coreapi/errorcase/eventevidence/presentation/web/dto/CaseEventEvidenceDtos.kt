package org.studieojavry.coreapi.errorcase.eventevidence.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant

@Schema(description = "Case 에 반영된 수집 근거(연결된 Hook 이벤트의 독립 스냅샷) 1건. 소유자 전용.")
data class CaseEventEvidenceResponse(
    val sourceClientEventId: String,
    val eventType: String,
    @Schema(description = "클라이언트 관측 시각")
    val observedAt: Instant,
    @Schema(description = "서버 수신 시각")
    val receivedAt: Instant,
    val truncated: Boolean,
    @Schema(description = "반영(검토 확정) 시각")
    val reviewedAt: Instant,
    @Schema(description = "마스킹된 payload 스냅샷(JSON).")
    val redactedPayload: JsonNode,
)

@Schema(description = "Case 수집 근거 목록(소유자 전용). 최신 반영 우선.")
data class CaseEventEvidenceListResponse(
    val items: List<CaseEventEvidenceResponse>,
)
