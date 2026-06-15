package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Service
class ListMyWorkspacesUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional(readOnly = true)
    fun invoke(userId: Long): List<Item> {
        val memberships = memberRepository.findByUserId(userId)
        if (memberships.isEmpty()) return emptyList()
        return memberships.mapNotNull { membership ->
            val ws = workspaceRepository.findById(membership.workspaceId) ?: return@mapNotNull null
            Item(
                workspaceId = ws.id!!,
                name = ws.name.value,
                slug = ws.slug.value,
                role = membership.role,
                createdByUserId = ws.createdByUserId
            )
        }.sortedByDescending { it.workspaceId }
    }

    data class Item(
        val workspaceId: Long,
        val name: String,
        val slug: String,
        val role: WorkspaceRole,
        val createdByUserId: Long
    )
}