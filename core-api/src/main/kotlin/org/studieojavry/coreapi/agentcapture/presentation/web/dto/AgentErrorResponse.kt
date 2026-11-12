package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * agent 경로 공통 에러 바디의 **문서용** 스키마.
 *
 * 실제 응답은 Spring 의 [org.springframework.http.ProblemDetail](RFC 7807, media type
 * `application/problem+json`)이며, 여기에 커넥터가 분기에 쓰는 안정적 식별자 `code` 와,
 * EVIDENCE_NOT_READY 에 한해 `missingEvidenceRefs` 를 추가로 싣는다
 * ([AgentCaptureExceptionHandler] 참조). 이 클래스는 OpenAPI 계약에서 그 모양을 노출하기 위한
 * 표현일 뿐 런타임에 직렬화되지 않는다.
 */
@Schema(
    name = "AgentErrorResponse",
    description = "agent 경로 공통 에러 바디(RFC 7807 ProblemDetail + 커넥터 분기용 `code`). " +
        "media type 는 `application/problem+json`.",
)
data class AgentErrorResponse(
    @field:Schema(description = "RFC 7807 문제 유형 URI.", example = "about:blank")
    val type: String? = null,
    @field:Schema(description = "HTTP 상태 사유구.", example = "Unprocessable Entity")
    val title: String? = null,
    @field:Schema(description = "HTTP 상태 코드.", example = "422")
    val status: Int = 0,
    @field:Schema(description = "사람이 읽는 상세 메시지.")
    val detail: String? = null,
    @field:Schema(description = "문제를 일으킨 요청 인스턴스 경로.")
    val instance: String? = null,
    @field:Schema(
        description = "커넥터가 분기에 쓰는 안정적 에러 코드.",
        example = "EVIDENCE_NOT_READY",
        allowableValues = [
            "CAPTURE_NOT_FOUND", "CAPTURE_SEALED", "REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT",
            "CONCURRENT_REQUEST", "ARTIFACT_HASH_MISMATCH", "ARTIFACT_TOO_LARGE", "UNSUPPORTED_ARTIFACT",
            "CAPTURE_NOT_REVIEWABLE", "CAPTURE_ALREADY_IMPORTED", "UNKNOWN_ATTEMPT_REFERENCE",
            "EVIDENCE_NOT_READY", "INVALID_REQUEST", "INTERNAL_ERROR",
        ],
    )
    val code: String,
    @field:Schema(
        description = "EVIDENCE_NOT_READY 에서 아직 준비되지 않은(업로드 안 된) 근거 참조 ID 목록. 그 외 코드에선 생략.",
    )
    val missingEvidenceRefs: List<String>? = null,
)
