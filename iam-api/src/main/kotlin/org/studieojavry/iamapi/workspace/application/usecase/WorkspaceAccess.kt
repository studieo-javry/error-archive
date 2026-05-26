package org.studieojavry.iamapi.workspace.application.usecase

import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

/**
 * 권한 체크 공용 가드. 컨트롤러가 아닌 유스케이스 계층에서 강제하기 위해 사용.
 * 도메인 메서드가 아닌 이유: 멤버 정보가 별도 애그리거트로 관리되므로 repository 조회가 필요.
 */
object WorkspaceAccess {

    fun requireMember(
        repository: WorkspaceMemberRepositoryPort,
        workspaceId: Long,
        userId: Long
    ): WorkspaceMember = repository.findByWorkspaceIdAndUserId(workspaceId, userId)
        ?: throw AccessDeniedException("not a member of workspace $workspaceId")

    fun requireAdmin(
        repository: WorkspaceMemberRepositoryPort,
        workspaceId: Long,
        userId: Long
    ): WorkspaceMember {
        val member = requireMember(repository, workspaceId, userId)
        if (member.role != WorkspaceRole.ADMIN) {
            throw AccessDeniedException("admin role required for workspace $workspaceId")
        }
        return member
    }

    class AccessDeniedException(message: String) : RuntimeException(message)
}