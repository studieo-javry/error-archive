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
        val personaIds = ensurePersonas(wsId)
        return DemoIdentity(workspaceId = wsId, botUserId = botId, personaUserIds = personaIds)
    }

    /**
     * 데모 시드 콘텐츠의 "여러 작성자" — 봇 하나로 몰면 목록 작성자 칩이 전부 같아 밋밋하다.
     * displayName 을 가진 실제 User 로 멱등 생성해 데모 WS 에 WRITE 멤버로 둔다(봇처럼 리셋에서 보존).
     * core-api 가 이 id 들을 시드 케이스 소유자로 분산한다.
     */
    private fun ensurePersonas(wsId: Long): List<Long> = PERSONAS.map { (handle, displayName) ->
        val id = userRepository.findByHandle(handle)?.id ?: run {
            try {
                userRepository.save(User.create(email = null, handle = handle, displayName = displayName, avatarUrl = null)).id!!
            } catch (ex: DataIntegrityViolationException) {
                userRepository.findByHandle(handle)?.id
                    ?: throw IllegalStateException("demo persona provisioning race not resolved ($handle)", ex)
            }
        }
        if (memberRepository.findByWorkspaceIdAndUserId(wsId, id) == null) {
            memberRepository.save(WorkspaceMember.join(wsId, id, WorkspaceRole.WRITE))
        }
        id
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

    data class DemoIdentity(
        val workspaceId: Long,
        val botUserId: Long,
        val personaUserIds: List<Long> = emptyList(),
    )

    private companion object {
        /** 데모 시드 작성자 persona (handle → displayName). 봇과 별개로 리셋에서 보존되는 고정 계정. */
        val PERSONAS: List<Pair<String, String>> = listOf(
            "demo-jiyeon" to "박지연",
            "demo-dohyun" to "한도윤",
            "demo-john" to "John Doe",
            "demo-mira" to "Mira K.",
            "demo-junho" to "이준호",
        )
    }
}
