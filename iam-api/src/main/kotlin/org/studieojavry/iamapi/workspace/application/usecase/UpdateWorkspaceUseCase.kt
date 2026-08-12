package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.command.UpdateWorkspaceCommand
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName

@Service
class UpdateWorkspaceUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort
) {

    @Transactional
    fun invoke(command: UpdateWorkspaceCommand) {
        val workspace = workspaceRepository.findById(command.workspaceId)
            ?: throw NoSuchElementException("workspace not found: ${command.workspaceId}")

        WorkspaceAccess.requireAdmin(memberRepository, command.workspaceId, command.actorUserId)

        command.name?.let { workspace.rename(WorkspaceName(it)) }

        val s = workspace.settings
        if (command.notificationEnabled != null || command.defaultTimezone != null) {
            workspace.updateSettings(
                s.copy(
                    notificationEnabled = command.notificationEnabled ?: s.notificationEnabled,
                    defaultTimezone = command.defaultTimezone ?: s.defaultTimezone
                )
            )
        }
        workspaceRepository.save(workspace)
    }
}