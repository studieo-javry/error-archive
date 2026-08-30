package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.command.ChangeMemberRoleCommand
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Service
class ChangeMemberRoleUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional
    fun invoke(command: ChangeMemberRoleCommand) {
        WorkspaceAccess.requireAdmin(memberRepository, command.workspaceId, command.actorUserId)

        val target = memberRepository.findByWorkspaceIdAndUserId(command.workspaceId, command.targetUserId)
            ?: throw NoSuchElementException("member not found in workspace ${command.workspaceId}: userId=${command.targetUserId}")

        if (target.role == command.newRole) return

        if (target.role == WorkspaceRole.ADMIN && command.newRole != WorkspaceRole.ADMIN) {
            ensureNotLastAdmin(command.workspaceId)
        }
        target.changeRole(command.newRole)
        memberRepository.save(target)
    }

    private fun ensureNotLastAdmin(workspaceId: Long) {
        val adminCount = memberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)
        if (adminCount <= 1) {
            throw LastAdminException(
                "워크스페이스에는 최소 1명의 관리자(Admin)가 필요합니다. 다른 멤버를 Admin 으로 지정한 뒤 역할을 변경하세요."
            )
        }
    }

    /** 마지막 남은 ADMIN 을 강등하려 할 때 — 도메인 규칙 위반. WorkspaceExceptionHandler 가 409 로 매핑. */
    class LastAdminException(message: String) : RuntimeException(message)
}