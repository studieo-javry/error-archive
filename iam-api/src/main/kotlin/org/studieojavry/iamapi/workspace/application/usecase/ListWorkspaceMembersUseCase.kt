package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Service
class ListWorkspaceMembersUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val memberSummaryReader: MemberSummaryReaderPort
) {

    @Transactional(readOnly = true)
    fun invoke(workspaceId: Long, viewerUserId: Long): List<Item> {
        workspaceRepository.findById(workspaceId)
            ?: throw NoSuchElementException("workspace not found: $workspaceId")
        WorkspaceAccess.requireMember(memberRepository, workspaceId, viewerUserId)

        val members = memberRepository.findByWorkspaceId(workspaceId)
        if (members.isEmpty()) return emptyList()

        val summaries = memberSummaryReader.findSummaries(members.map { it.userId }).associateBy { it.userId }
        // 정렬: ADMIN 먼저, 그 다음 가입일 오름차순
        return members
            .sortedWith(compareByDescending<org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember> { it.role.rank }.thenBy { it.joinedAt })
            .mapNotNull { m ->
                val s = summaries[m.userId] ?: return@mapNotNull Item(
                    userId = m.userId,
                    displayName = "(unknown)",
                    avatarUrl = null,
                    role = m.role,
                    joinedAt = m.joinedAt
                )
                Item(
                    userId = m.userId,
                    displayName = s.displayName,
                    avatarUrl = s.avatarUrl,
                    role = m.role,
                    joinedAt = m.joinedAt
                )
            }
    }

    data class Item(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val role: WorkspaceRole,
        val joinedAt: Instant
    )
}