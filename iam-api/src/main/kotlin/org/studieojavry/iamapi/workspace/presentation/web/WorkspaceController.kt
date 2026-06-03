package org.studieojavry.iamapi.workspace.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.workspace.application.command.CreateWorkspaceCommand
import org.studieojavry.iamapi.workspace.application.command.UpdateWorkspaceCommand
import org.studieojavry.iamapi.workspace.application.usecase.CreateWorkspaceUseCase
import org.studieojavry.iamapi.workspace.application.usecase.DeleteWorkspaceUseCase
import org.studieojavry.iamapi.workspace.application.usecase.GetWorkspaceUseCase
import org.studieojavry.iamapi.workspace.application.usecase.ListMyWorkspacesUseCase
import org.studieojavry.iamapi.workspace.application.usecase.UpdateWorkspaceUseCase
import org.studieojavry.iamapi.workspace.presentation.web.dto.request.CreateWorkspaceRequest
import org.studieojavry.iamapi.workspace.presentation.web.dto.request.UpdateWorkspaceRequest
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.WorkspaceListItemResponse
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.WorkspaceResponse

@Tag(name = "workspaces", description = "워크스페이스 CRUD + 내 목록. 멤버(READ+) 만 조회, ADMIN 만 수정/삭제.")
@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController(
    private val createWorkspaceUseCase: CreateWorkspaceUseCase,
    private val updateWorkspaceUseCase: UpdateWorkspaceUseCase,
    private val deleteWorkspaceUseCase: DeleteWorkspaceUseCase,
    private val getWorkspaceUseCase: GetWorkspaceUseCase,
    private val listMyWorkspacesUseCase: ListMyWorkspacesUseCase
) {

    @Operation(summary = "워크스페이스 생성", description = "생성자가 자동으로 ADMIN 으로 멤버 등록됨.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성됨(상세 반환, viewerRole=ADMIN)"),
        ApiResponse(responseCode = "400", description = "이름 검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateWorkspaceRequest
    ): WorkspaceResponse {
        val me = currentUserId(jwt)
        val created = createWorkspaceUseCase.invoke(
            CreateWorkspaceCommand(createdByUserId = me, name = request.name)
        )
        val detail = getWorkspaceUseCase.invoke(created.workspaceId, me)
        return WorkspaceResponse(
            workspaceId = detail.workspaceId,
            name = detail.name,
            createdByUserId = detail.createdByUserId,
            notificationEnabled = detail.notificationEnabled,
            defaultTimezone = detail.defaultTimezone,
            createdAt = detail.createdAt,
            updatedAt = detail.updatedAt,
            viewerRole = detail.viewerRole.name
        )
    }

    @Operation(summary = "내 워크스페이스 목록", description = "내가 멤버인 워크스페이스 + 각 워크스페이스에서의 내 역할.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"), ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]))
    @GetMapping
    fun listMine(@Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt): List<WorkspaceListItemResponse> {
        val me = currentUserId(jwt)
        return listMyWorkspacesUseCase.invoke(me).map {
            WorkspaceListItemResponse(it.workspaceId, it.name, it.role.name, it.createdByUserId)
        }
    }

    @Operation(summary = "워크스페이스 상세 조회", description = "응답에 viewerRole 포함(요청자가 그 워크스페이스에서의 역할).")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "비멤버", content = [Content()]),
        ApiResponse(responseCode = "404", description = "워크스페이스 없음", content = [Content()])
    )
    @GetMapping("/{workspaceId}")
    fun get(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long
    ): WorkspaceResponse {
        val me = currentUserId(jwt)
        val r = getWorkspaceUseCase.invoke(workspaceId, me)
        return WorkspaceResponse(
            workspaceId = r.workspaceId,
            name = r.name,
            createdByUserId = r.createdByUserId,
            notificationEnabled = r.notificationEnabled,
            defaultTimezone = r.defaultTimezone,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
            viewerRole = r.viewerRole.name
        )
    }

    @Operation(summary = "워크스페이스 부분 수정", description = "ADMIN 만. null/미포함 필드는 유지.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 후 상세 반환"),
        ApiResponse(responseCode = "400", description = "검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님", content = [Content()])
    )
    @PatchMapping("/{workspaceId}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long,
        @Valid @RequestBody request: UpdateWorkspaceRequest
    ): WorkspaceResponse {
        val me = currentUserId(jwt)
        updateWorkspaceUseCase.invoke(
            UpdateWorkspaceCommand(
                workspaceId = workspaceId,
                actorUserId = me,
                name = request.name,
                notificationEnabled = request.notificationEnabled,
                defaultTimezone = request.defaultTimezone
            )
        )
        return get(jwt, workspaceId)
    }

    @Operation(summary = "워크스페이스 삭제", description = "생성자만(현행). 멤버십·초대·연결된 콘텐츠 영향은 도메인별로 별도 처리.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "생성자 아님", content = [Content()])
    )
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long
    ) {
        val me = currentUserId(jwt)
        deleteWorkspaceUseCase.invoke(workspaceId, me)
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
