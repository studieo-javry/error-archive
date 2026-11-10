package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateProjectBindingCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateProjectBindingUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListProjectBindingsUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CreateProjectBindingRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ProjectBindingListResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ProjectBindingResponse

/**
 * 프로젝트 바인딩 관리(웹 세션 사용자 전용). 로컬경로↔프로젝트↔repo 를 사용자가 명시적으로 등록한다.
 * 에이전트 토큰(/api/v1/agent/…)이 아니라 일반 웹 인증 — 본인 소유 바인딩만.
 */
@Tag(name = "project-binding", description = "프로젝트 바인딩 생성·조회 — 세션 사용자 전용(P1 토대).")
@RestController
@RequestMapping("/api/v1/me/project-bindings")
class ProjectBindingController(
    private val createBinding: CreateProjectBindingUseCase,
    private val listBindings: ListProjectBindingsUseCase,
) {

    @Operation(summary = "바인딩 생성", description = "projectKey(+선택 repo) 등록. 같은 projectKey 가 있으면 기존 반환(멱등).")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성됨"),
        ApiResponse(responseCode = "200", description = "이미 존재(멱등 반환)"),
    )
    @PostMapping
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateProjectBindingRequest,
    ): ResponseEntity<ProjectBindingResponse> {
        val result = createBinding.invoke(
            CreateProjectBindingCommand(
                ownerUserId = userId,
                projectKey = request.projectKey,
                normalizedRepoId = request.normalizedRepoId,
            ),
        )
        return ResponseEntity.status(if (result.created) 201 else 200).body(ProjectBindingResponse.from(result.binding))
    }

    @Operation(summary = "바인딩 목록", description = "본인 소유 프로젝트 바인딩(projectKey 순).")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
    ): ProjectBindingListResponse =
        ProjectBindingListResponse(items = listBindings.invoke(userId).map(ProjectBindingResponse::from))
}
