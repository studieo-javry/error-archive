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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.workspace.application.command.ChangeMemberRoleCommand
import org.studieojavry.iamapi.workspace.application.usecase.ChangeMemberRoleUseCase
import org.studieojavry.iamapi.workspace.application.usecase.ListWorkspaceMembersUseCase
import org.studieojavry.iamapi.workspace.application.usecase.RemoveMemberUseCase
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import org.studieojavry.iamapi.workspace.presentation.web.dto.request.ChangeMemberRoleRequest
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.WorkspaceMemberResponse

@Tag(name = "workspace-members", description = "워크스페이스 멤버 목록/역할 변경/제거/탈퇴. ADMIN 만 타인 관리.")
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/members")
class WorkspaceMemberController(
    private val listWorkspaceMembersUseCase: ListWorkspaceMembersUseCase,
    private val changeMemberRoleUseCase: ChangeMemberRoleUseCase,
    private val removeMemberUseCase: RemoveMemberUseCase
) {

    @Operation(summary = "멤버 목록", description = "워크스페이스 멤버(READ+) 만 조회 가능.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "비멤버", content = [Content()])
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long
    ): List<WorkspaceMemberResponse> {
        val me = currentUserId(jwt)
        return listWorkspaceMembersUseCase.invoke(workspaceId, me).map {
            WorkspaceMemberResponse(
                userId = it.userId,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl,
                role = it.role.name,
                joinedAt = it.joinedAt,
                profileHref = "/users/${it.userId}"
            )
        }
    }

    @Operation(
        summary = "멤버 역할 변경",
        description = "ADMIN 만. role: 1=READ, 2=WRITE, 3=ADMIN. 마지막 ADMIN 강등은 거부(403)."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "변경 완료"),
        ApiResponse(responseCode = "400", description = "role 값 유효하지 않음", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님 / 마지막 ADMIN 강등 시도", content = [Content()]),
        ApiResponse(responseCode = "404", description = "대상 멤버 없음", content = [Content()])
    )
    @PatchMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long,
        @Parameter(description = "대상 사용자 ID") @PathVariable userId: Long,
        @Valid @RequestBody request: ChangeMemberRoleRequest
    ) {
        val me = currentUserId(jwt)
        changeMemberRoleUseCase.invoke(
            ChangeMemberRoleCommand(
                workspaceId = workspaceId,
                actorUserId = me,
                targetUserId = userId,
                newRole = WorkspaceRole.fromCode(request.role)
            )
        )
    }

    @Operation(summary = "멤버 제거 (강제 퇴출)", description = "ADMIN 만. 본인 탈퇴는 `/members/me` 사용.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님 / 마지막 ADMIN 제거 시도", content = [Content()])
    )
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long,
        @Parameter(description = "제거할 사용자 ID") @PathVariable userId: Long
    ) {
        val me = currentUserId(jwt)
        removeMemberUseCase.invoke(workspaceId, me, userId)
    }

    @Operation(summary = "내가 탈퇴", description = "본인 탈퇴. 마지막 ADMIN 이면 거부(다른 ADMIN 임명 후 탈퇴 필요).")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "탈퇴 완료"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "마지막 ADMIN 이라 탈퇴 불가", content = [Content()])
    )
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long
    ) {
        val me = currentUserId(jwt)
        removeMemberUseCase.invoke(workspaceId, me, me)
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
