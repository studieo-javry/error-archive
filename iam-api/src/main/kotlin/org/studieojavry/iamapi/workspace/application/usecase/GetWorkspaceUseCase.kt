package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Service
class GetWorkspaceUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional(readOnly = true)
    fun invoke(workspaceId: Long, viewerUserId: Long): Result {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw NoSuchElementException("workspace not found: $workspaceId")
        val viewerMember = WorkspaceAccess.requireMember(memberRepository, workspaceId, viewerUserId)

        return Result(
            workspaceId = workspace.id!!,
            name = workspace.name.value,
            slug = workspace.slug.value,
            createdByUserId = workspace.createdByUserId,
            notificationEnabled = workspace.settings.notificationEnabled,
            defaultTimezone = workspace.settings.defaultTimezone,
            createdAt = workspace.createdAt,
            updatedAt = workspace.updatedAt,
            viewerRole = viewerMember.role
        )
    }

    data class Result(
        val workspaceId: Long,
        val name: String,
        val slug: String,
        val createdByUserId: Long,
        val notificationEnabled: Boolean,
        val defaultTimezone: String,
        val createdAt: Instant,
        val updatedAt: Instant,
        val viewerRole: WorkspaceRole
    )
}