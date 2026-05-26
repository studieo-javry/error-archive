package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.command.CreateWorkspaceCommand
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.Workspace
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

@Service
class CreateWorkspaceUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val memberSummaryReader: MemberSummaryReaderPort
) {

    @Transactional
    fun invoke(command: CreateWorkspaceCommand): Result {
        if (!memberSummaryReader.existsActive(command.createdByUserId)) {
            throw NoSuchElementException("user not found: ${command.createdByUserId}")
        }
        val saved = workspaceRepository.save(
            Workspace.create(
                name = WorkspaceName(command.name),
                createdByUserId = command.createdByUserId
            )
        )
        memberRepository.save(
            WorkspaceMember.join(
                workspaceId = saved.id!!,
                userId = command.createdByUserId,
                role = WorkspaceRole.ADMIN
            )
        )
        return Result(workspaceId = saved.id, name = saved.name.value)
    }

    data class Result(val workspaceId: Long, val name: String)
}