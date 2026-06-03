package org.studieojavry.iamapi.workspace.application.port

import org.studieojavry.iamapi.workspace.domain.model.WorkspaceInvitation
import java.time.Instant

interface WorkspaceInvitationRepositoryPort {
    fun save(invitation: WorkspaceInvitation): WorkspaceInvitation
    fun findById(id: Long): WorkspaceInvitation?
    fun findByTokenHash(tokenHash: String): WorkspaceInvitation?
    fun findPendingByWorkspaceId(workspaceId: Long): List<WorkspaceInvitation>
    fun deleteAllByWorkspaceId(workspaceId: Long)

    /**
     * 토큰을 1회용으로 원자적 점유(PENDING+미만료 → ACCEPTED). 점유 성공 시 true.
     * false 면 이미 사용/취소/만료(또는 존재하지 않음) → 수락 거부.
     */
    fun claimForAcceptance(tokenHash: String, acceptingUserId: Long, now: Instant): Boolean
}
