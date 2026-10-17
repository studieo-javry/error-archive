package org.studieojavry.iamapi.demo.application

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.Workspace
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceName
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceSlug

/**
 * 데모 봇 + 공유 데모 워크스페이스를 **멱등 보장**한다(없으면 생성, 있으면 재사용).
 * 게스트 로그인·리셋·identity 조회가 모두 이걸 먼저 호출해 ID 를 얻는다.
 */
@Service
class DemoWorkspaceProvisioner(
    private val userRepository: UserRepositoryPort,
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val props: DemoProperties,
) {

    @Transactional
    fun ensureProvisioned(): DemoIdentity {
        val botId = ensureBot()
        val wsId = ensureWorkspace(botId)
        if (memberRepository.findByWorkspaceIdAndUserId(wsId, botId) == null) {
            memberRepository.save(WorkspaceMember.join(wsId, botId, WorkspaceRole.ADMIN))
        }
        return DemoIdentity(workspaceId = wsId, botUserId = botId)
    }

    private fun ensureBot(): Long {
        userRepository.findByHandle(props.botHandle)?.let { return it.id!! }
        return try {
            userRepository.save(
                User.create(
                    email = null,
                    handle = props.botHandle,
                    displayName = props.botDisplayName,
                    avatarUrl = null,
                ),
            ).id!!
        } catch (ex: DataIntegrityViolationException) {
            // 콜드스타트 동시 생성 race — unique(handle) 가 두 번째를 reject. 재조회로 수렴.
            userRepository.findByHandle(props.botHandle)?.id
                ?: throw IllegalStateException("demo bot provisioning race not resolved", ex)
        }
    }

    private fun ensureWorkspace(botId: Long): Long {
        workspaceRepository.findBySlug(props.workspaceSlug)?.let { return it.id!! }
        return try {
            workspaceRepository.save(
                Workspace.create(
                    name = WorkspaceName(props.workspaceName),
                    slug = WorkspaceSlug(props.workspaceSlug),
                    createdByUserId = botId,
                ),
            ).id!!
        } catch (ex: DataIntegrityViolationException) {
            workspaceRepository.findBySlug(props.workspaceSlug)?.id
                ?: throw IllegalStateException("demo workspace provisioning race not resolved", ex)
        }
    }

    data class DemoIdentity(val workspaceId: Long, val botUserId: Long)
}
