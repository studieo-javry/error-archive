package org.studieojavry.iamapi.workspace.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

/**
 * 회원이 워크스페이스를 떠날 때(탈퇴 또는 외부 요청)의 멤버십 처리 로직.
 *
 * 떠나는 사용자가 ADMIN 이고 그 워크스페이스의 마지막 ADMIN 이면 **다음 멤버를 자동으로 ADMIN 으로 승격**
 * 후 본인을 제거한다. 다른 ADMIN 이 있으면 본인만 제거. 사용자가 유일 멤버면 워크스페이스도 hard-delete.
 *
 * 자동 승격 우선순위(rank 높음 + 가입 오래된 순):
 *   1) WRITE 멤버 중 가장 오래된 사람 → ADMIN
 *   2) WRITE 가 없으면 READ 멤버 중 가장 오래된 사람 → ADMIN
 *   3) 멤버가 본인뿐 → 워크스페이스 삭제(콘텐츠는 core-api 가 따로 보존)
 */
@Service
class TransferMembershipsOnUserLeaveUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val workspaceRepository: WorkspaceRepositoryPort,
) {
    private val log = KotlinLogging.logger {}

    /** 사용자의 **모든** 워크스페이스 멤버십 정리. 호출자가 이미 트랜잭션 안에 있다고 가정(Propagation.REQUIRED). */
    @Transactional
    fun handleLeaveAll(userId: Long) {
        memberRepository.findByUserId(userId).forEach { membership ->
            handleSingleLeave(membership.workspaceId, membership)
        }
    }

    private fun handleSingleLeave(workspaceId: Long, leavingMember: WorkspaceMember) {
        val all = memberRepository.findByWorkspaceId(workspaceId)
        val others = all.filter { it.userId != leavingMember.userId }

        if (others.isEmpty()) {
            // 유일 멤버 → 워크스페이스도 hard-delete
            memberRepository.delete(workspaceId, leavingMember.userId)
            workspaceRepository.delete(workspaceId)
            log.info { "[leave] workspace=$workspaceId deleted (sole member user=${leavingMember.userId} left)" }
            return
        }

        val isSoleAdmin = leavingMember.role == WorkspaceRole.ADMIN &&
            all.count { it.role == WorkspaceRole.ADMIN } == 1
        if (isSoleAdmin) {
            val promotee = others.sortedWith(compareByDescending<WorkspaceMember> { it.role.rank }.thenBy { it.joinedAt }).first()
            promotee.changeRole(WorkspaceRole.ADMIN)
            memberRepository.save(promotee)
            log.info {
                "[leave] workspace=$workspaceId auto-promoted user=${promotee.userId} to ADMIN (replacing user=${leavingMember.userId})"
            }
        }
        memberRepository.delete(workspaceId, leavingMember.userId)
    }
}
