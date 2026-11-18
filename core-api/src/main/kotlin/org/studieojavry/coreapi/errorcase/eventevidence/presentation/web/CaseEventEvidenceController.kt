package org.studieojavry.coreapi.errorcase.eventevidence.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.eventevidence.application.usecase.CaseEventEvidenceForbiddenException
import org.studieojavry.coreapi.errorcase.eventevidence.application.usecase.ListCaseEventEvidenceUseCase
import org.studieojavry.coreapi.errorcase.eventevidence.domain.model.CaseEventEvidence
import org.studieojavry.coreapi.errorcase.eventevidence.presentation.web.dto.CaseEventEvidenceListResponse
import org.studieojavry.coreapi.errorcase.eventevidence.presentation.web.dto.CaseEventEvidenceResponse
import tools.jackson.databind.ObjectMapper

/**
 * Case 수집 근거 조회(PR-2) — 검토에서 반영한 연결 이벤트 스냅샷을 Case 상세에서 **소유자 전용**으로 열람.
 * 발행/공유에는 자동 포함되지 않는다(원시 관측엔 로컬경로·명령·입력이 섞일 수 있어 노출은 별도 결정).
 */
@Tag(name = "error-case-event-evidence", description = "Case 에 반영된 수집 근거(연결 이벤트 스냅샷) 조회 — 소유자 전용.")
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/event-evidence")
class CaseEventEvidenceController(
    private val listEvidence: ListCaseEventEvidenceUseCase,
    private val objectMapper: ObjectMapper,
) {

    @Operation(
        summary = "Case 수집 근거 조회 (소유자 전용)",
        description = "검토 반영 시 선택한, 이 Case 에 귀속된 수집 근거(연결 이벤트의 독립 스냅샷)를 최신순 반환한다. " +
            "소유자만 열람(발행/공유 노출 아님).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공(없으면 빈 목록)"),
        ApiResponse(responseCode = "403", description = "소유자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "Case 없음", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable caseId: Long,
    ): CaseEventEvidenceListResponse {
        val items = try {
            listEvidence.invoke(caseId, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CaseEventEvidenceForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return CaseEventEvidenceListResponse(items = items.map(::toResponse))
    }

    private fun toResponse(e: CaseEventEvidence): CaseEventEvidenceResponse = CaseEventEvidenceResponse(
        sourceClientEventId = e.sourceClientEventId,
        eventType = e.eventType,
        observedAt = e.observedAt,
        receivedAt = e.receivedAt,
        truncated = e.truncated,
        reviewedAt = e.reviewedAt,
        redactedPayload = objectMapper.readTree(e.redactedPayload),
    )
}
