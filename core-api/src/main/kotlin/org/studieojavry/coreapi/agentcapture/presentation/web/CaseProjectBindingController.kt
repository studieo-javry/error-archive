package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.application.usecase.AssignCaseProjectBindingUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AssignCaseProjectBindingRequest

/**
 * 기존 Case 의 ProjectBinding 사후 귀속/해제 — 웹 세션 사용자(본인 소유)만. 에이전트 토큰 아님.
 *
 * #162 는 import 시점에만 신규 Case 를 귀속시켰다. 이 엔드포인트로 **이미 만들어진 Case** 를
 * 프로젝트에 명시 연결해, 에이전트 case:read(find_cases/get_case)가 과거 관련 Case 를 끌어올 수 있다.
 */
@Tag(name = "project-binding", description = "기존 Case 를 ProjectBinding 에 귀속/해제 — 세션 사용자 전용.")
@RestController
@RequestMapping("/api/v1/me/cases/{caseId}/project-binding")
class CaseProjectBindingController(
    private val assign: AssignCaseProjectBindingUseCase,
) {

    @Operation(
        summary = "Case 를 ProjectBinding 에 귀속",
        description = "본인 소유 Case 를 본인 소유 ProjectBinding 에 귀속(재귀속 시 덮어씀). 성공 시 204.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "귀속됨", content = [Content()]),
        ApiResponse(responseCode = "404", description = "CASE_NOT_FOUND(없음/타인 소유) 또는 PROJECT_BINDING_NOT_FOUND", content = [Content()]),
    )
    @PutMapping
    fun bind(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable caseId: Long,
        @Valid @RequestBody request: AssignCaseProjectBindingRequest,
    ): ResponseEntity<Void> {
        assign.bind(userId, caseId, request.projectBindingId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Case 의 ProjectBinding 귀속 해제",
        description = "미귀속으로 되돌림(에이전트 case:read 대상에서 제외). 멱등 — 이미 미귀속이어도 204.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제됨", content = [Content()]),
        ApiResponse(responseCode = "404", description = "CASE_NOT_FOUND(없음/타인 소유)", content = [Content()]),
    )
    @DeleteMapping
    fun unbind(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable caseId: Long,
    ): ResponseEntity<Void> {
        assign.unbind(userId, caseId)
        return ResponseEntity.noContent().build()
    }
}
