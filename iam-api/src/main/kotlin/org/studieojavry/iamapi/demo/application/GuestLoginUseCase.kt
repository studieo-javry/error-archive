package org.studieojavry.iamapi.demo.application

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

/**
 * "로그인 없이 둘러보기" — 방문자마다 새 유저를 만들지 않고 **고정 공유 게스트 하나**(handle=guestHandle)를
 * 재사용해 토큰만 발급한다. 데모는 브라우즈 전용(게스트 쓰기는 실제 저장 X)이라 공유해도 충돌이 없고,
 * 매번 생성·삭제·누적(멤버 페이지/DB) 문제가 사라진다.
 *
 * - 공유 게스트는 데모 WS `WRITE` 멤버(멱등), `roles=["GUEST"]` access 토큰(TTL, refresh 없음) 발급.
 * - 고정 계정이므로 야간 게스트 리셋에서 **제외**(DemoResetService). displayName 은 guestDisplayName 고정.
 * - 트레이드오프: 방문자 간 서버측 상태 격리는 없음(진짜 per-visitor 기록이 필요해지면 재설계).
 */
@Service
class GuestLoginUseCase(
    private val provisioner: DemoWorkspaceProvisioner,
    private val userRepository: UserRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val jwtIssuer: JwtIssuerPort,
    private val props: DemoProperties,
) {

    @Transactional
    fun invoke(): Result {
        check(props.enabled) { "demo guest access is disabled" }
        val demo = provisioner.ensureProvisioned()
        val guest = ensureSharedGuest(demo.workspaceId)

        val expiresAt = Instant.now().plusSeconds(props.guestTokenTtlSeconds)
        val accessToken = jwtIssuer.issueGuestAccessToken(userId = guest.id!!, expiresAt = expiresAt)

        return Result(
            userId = guest.id,
            accessToken = accessToken,
            accessTokenExpiresAt = expiresAt,
            workspaceId = demo.workspaceId,
            workspaceSlug = props.workspaceSlug,
            displayName = guest.displayName,
        )
    }

    /** 공유 게스트를 멱등 보장(없으면 생성, 있으면 재사용) + 데모 WS WRITE 멤버 보장. 콜드스타트 동시 생성 race 수렴. */
    private fun ensureSharedGuest(workspaceId: Long): User {
        val guest = userRepository.findByHandle(props.guestHandle) ?: run {
            try {
                userRepository.save(
                    User.createGuest(handle = props.guestHandle, displayName = props.guestDisplayName)
                        .also { it.changeDefaultWorkspace(workspaceId) },
                )
            } catch (ex: DataIntegrityViolationException) {
                userRepository.findByHandle(props.guestHandle)
                    ?: throw IllegalStateException("shared demo guest provisioning race not resolved", ex)
            }
        }
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, guest.id!!) == null) {
            memberRepository.save(WorkspaceMember.join(workspaceId, guest.id, WorkspaceRole.WRITE))
        }
        return guest
    }

    data class Result(
        val userId: Long,
        val accessToken: String,
        val accessTokenExpiresAt: Instant,
        val workspaceId: Long,
        val workspaceSlug: String,
        val displayName: String,
    )
}
