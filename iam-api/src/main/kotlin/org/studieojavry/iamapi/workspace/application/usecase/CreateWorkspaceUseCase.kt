package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.dao.DataIntegrityViolationException
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
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSlug

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
        val slug = WorkspaceSlug(command.slug)
        if (workspaceRepository.existsBySlug(slug.value)) {
            throw DuplicateSlugException(slug.value)
        }
        val saved = try {
            workspaceRepository.save(
                Workspace.create(
                    name = WorkspaceName(command.name),
                    slug = slug,
                    createdByUserId = command.createdByUserId,
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            // 동시 생성 race — DB unique constraint 가 두 번째 commit 을 reject.
            throw DuplicateSlugException(slug.value, ex)
        }
        memberRepository.save(
            WorkspaceMember.join(
                workspaceId = saved.id!!,
                userId = command.createdByUserId,
                role = WorkspaceRole.ADMIN
            )
        )
        return Result(workspaceId = saved.id, name = saved.name.value, slug = saved.slug.value)
    }

    data class Result(val workspaceId: Long, val name: String, val slug: String)

    class DuplicateSlugException(val slug: String, cause: Throwable? = null) :
        RuntimeException("workspace slug already in use: $slug", cause)
}
