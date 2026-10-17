package org.studieojavry.iamapi.demo.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

/**
 * "로그인 없이 둘러보기" — **방문자마다 임시 게스트 사용자**를 만들어(이름 구분용) 공유 데모 워크스페이스에
 * `WRITE` 로 가입시키고 `roles=["GUEST"]` access 토큰을 발급한다. refresh 없음.
 *
 * - 쓰기는 **에러케이스/댓글 생성**까지 허용(WRITE). 초대/공개승격은 ADMIN 전용이라 역할만으로 자동 차단.
 *   업로드/발행은 다운스트림에서 `ROLE_GUEST` 로 추가 차단(비용/외부노출).
 * - 생성된 콘텐츠는 야간 리셋으로 정리.
 */
@Service
class GuestLoginUseCase(
    private val provisioner: DemoWorkspaceProvisioner,
    private val userRepository: UserRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val jwtIssuer: JwtIssuerPort,
    private val secureRandom: SecureRandomPort,
    private val props: DemoProperties,
) {

    @Transactional
    fun invoke(): Result {
        check(props.enabled) { "demo guest access is disabled" }
        val demo = provisioner.ensureProvisioned()

        val suffix = uniqueSuffix()
        val guest = userRepository.save(
            User.createGuest(
                handle = "guest-$suffix",
                displayName = "게스트-${suffix.take(4).uppercase()}",
            ).also { it.changeDefaultWorkspace(demo.workspaceId) },
        )
        memberRepository.save(WorkspaceMember.join(demo.workspaceId, guest.id!!, WorkspaceRole.WRITE))

        val expiresAt = Instant.now().plusSeconds(props.guestTokenTtlSeconds)
        val accessToken = jwtIssuer.issueGuestAccessToken(userId = guest.id, expiresAt = expiresAt)

        return Result(
            userId = guest.id,
            accessToken = accessToken,
            accessTokenExpiresAt = expiresAt,
            workspaceId = demo.workspaceId,
            workspaceSlug = props.workspaceSlug,
            displayName = guest.displayName,
        )
    }

    /** `guest-<랜덤 alnum>` 핸들 — 충돌 시(극히 드묾) 재시도. (SecureRandom 은 최소 16바이트 요구.) */
    private fun uniqueSuffix(): String {
        repeat(5) {
            val s = secureRandom.generateUrlSafeToken(16)
                .lowercase().replace(Regex("[^a-z0-9]"), "").take(10)
                .ifBlank { return@repeat }
            if (!userRepository.existsByHandle("guest-$s")) return s
        }
        // 최후 fallback — 타임스탬프 기반(충돌 사실상 0).
        return System.nanoTime().toString(36).lowercase().take(10)
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
