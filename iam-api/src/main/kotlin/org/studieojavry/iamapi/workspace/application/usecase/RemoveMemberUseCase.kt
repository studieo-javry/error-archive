package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Service
class RemoveMemberUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    /**
     * targetUserId == actorUserId → self leave 허용. 그 외엔 ADMIN만.
     * 마지막 ADMIN은 떠날 수 없음.
     */
    @Transactional
    fun invoke(workspaceId: Long, actorUserId: Long, targetUserId: Long) {
        val isSelfLeave = actorUserId == targetUserId
        if (!isSelfLeave) {
            WorkspaceAccess.requireAdmin(memberRepository, workspaceId, actorUserId)
        }
        val target = memberRepository.findByWorkspaceIdAndUserId(workspaceId, targetUserId)
            ?: throw NoSuchElementException("member not found in workspace $workspaceId")

        if (target.role == WorkspaceRole.ADMIN) {
            val adminCount = memberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)
            if (adminCount <= 1) {
                throw IllegalStateException("workspace must keep at least one ADMIN")
            }
        }
        memberRepository.delete(workspaceId, targetUserId)
    }
}