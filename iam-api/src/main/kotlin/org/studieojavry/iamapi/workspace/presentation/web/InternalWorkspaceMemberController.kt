package org.studieojavry.iamapi.workspace.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.workspace.application.usecase.ListWorkspaceCoMembersUseCase
import org.studieojavry.iamapi.workspace.application.usecase.ListWorkspaceMemberIdsUseCase

/**
 * **Internal-only** — 워크스페이스 member userId list batch 조회.
 *
 * core-api 가 case RESOLVED 알림 fan-out 시 호출. gateway 라우팅 X. internal JWT(aud=iam-api) 검증.
 */
@Tag(name = "workspaces-internal", description = "service-to-service: 워크스페이스 멤버 userId 조회.")
@RestController
@RequestMapping("/internal/workspaces")
class InternalWorkspaceMemberController(
    private val listWorkspaceMemberIds: ListWorkspaceMemberIdsUseCase,
    private val listWorkspaceCoMembers: ListWorkspaceCoMembersUseCase,
) {
    @Operation(summary = "[internal] 워크스페이스 멤버 userId 목록")
    @GetMapping("/{workspaceId}/member-ids")
    fun memberIds(@PathVariable workspaceId: Long): MemberIdsResponse {
        return MemberIdsResponse(userIds = listWorkspaceMemberIds.invoke(workspaceId))
    }

    @Operation(summary = "[internal] userId 의 같은 워크스페이스 멤버 userIds (자기 제외, distinct)")
    @GetMapping("/co-members")
    fun coMembers(
        @RequestParam userId: Long,
        @RequestParam(defaultValue = "200") size: Int,
    ): MemberIdsResponse {
        return MemberIdsResponse(userIds = listWorkspaceCoMembers.invoke(userId, size))
    }

    data class MemberIdsResponse(val userIds: List<Long>)
}
