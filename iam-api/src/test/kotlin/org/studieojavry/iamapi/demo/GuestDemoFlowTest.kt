package org.studieojavry.iamapi.demo

import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.demo.application.DemoResetService
import org.studieojavry.iamapi.demo.application.DemoWorkspaceProvisioner
import org.studieojavry.iamapi.demo.application.GuestLoginUseCase
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

/**
 * "로그인 없이 둘러보기" 핵심 흐름 검증(H2 in-memory, 실제 Spring context).
 *  - 게스트 로그인: 임시 사용자(isGuest=true) + 데모 WS WRITE 가입 + roles=["GUEST"] 토큰
 *  - 방문자별 구분: 두 게스트는 서로 다른 사용자
 *  - 야간 리셋: 게스트만 삭제, 데모 봇/워크스페이스는 보존
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true"])
@Transactional   // 테스트별 롤백으로 게스트 누적 격리(데모 봇/WS 는 기동 시 커밋돼 유지)
class GuestDemoFlowTest(
    @Autowired private val guestLogin: GuestLoginUseCase,
    @Autowired private val resetService: DemoResetService,
    @Autowired private val provisioner: DemoWorkspaceProvisioner,
    @Autowired private val userRepository: UserRepositoryPort,
    @Autowired private val workspaceRepository: WorkspaceRepositoryPort,
    @Autowired private val memberRepository: WorkspaceMemberRepositoryPort,
) {

    @Test
    fun `게스트 로그인은 WRITE 멤버 + GUEST 토큰을 발급한다`() {
        val r = guestLogin.invoke()

        val user = userRepository.findById(r.userId)
        assertThat(user).isNotNull
        assertThat(user!!.isGuest).isTrue()
        assertThat(user.handle).startsWith("guest-")
        assertThat(r.displayName).startsWith("게스트-")

        val member = memberRepository.findByWorkspaceIdAndUserId(r.workspaceId, r.userId)
        assertThat(member).isNotNull
        assertThat(member!!.role).isEqualTo(WorkspaceRole.WRITE)

        // 토큰 claim: roles=["GUEST"], typ=access, sub=userId
        val claims = SignedJWT.parse(r.accessToken).jwtClaimsSet
        assertThat(claims.getStringListClaim("roles")).containsExactly("GUEST")
        assertThat(claims.getStringClaim("typ")).isEqualTo("access")
        assertThat(claims.subject).isEqualTo(r.userId.toString())

        // 진입 워크스페이스는 멱등 데모 WS 와 동일
        assertThat(r.workspaceId).isEqualTo(provisioner.ensureProvisioned().workspaceId)
        assertThat(r.workspaceSlug).isEqualTo("demo")
    }

    @Test
    fun `방문자마다 서로 다른 임시 사용자가 생성된다`() {
        val a = guestLogin.invoke()
        val b = guestLogin.invoke()

        assertThat(a.userId).isNotEqualTo(b.userId)
        assertThat(userRepository.findById(a.userId)!!.handle)
            .isNotEqualTo(userRepository.findById(b.userId)!!.handle)
        // 둘 다 같은 데모 WS 공유
        assertThat(a.workspaceId).isEqualTo(b.workspaceId)
    }

    @Test
    fun `리셋은 게스트만 지우고 데모 봇·워크스페이스는 보존한다`() {
        guestLogin.invoke()
        guestLogin.invoke()
        assertThat(userRepository.findGuests(100)).hasSize(2)

        val demo = provisioner.ensureProvisioned()
        val removed = resetService.resetGuests()

        assertThat(removed).isEqualTo(2)
        assertThat(userRepository.findGuests(100)).isEmpty()
        // 데모 봇·워크스페이스·봇 멤버십은 그대로
        assertThat(userRepository.findByHandle("demo-bot")).isNotNull
        assertThat(workspaceRepository.findBySlug("demo")).isNotNull
        val botMember = memberRepository.findByWorkspaceIdAndUserId(demo.workspaceId, demo.botUserId)
        assertThat(botMember!!.role).isEqualTo(WorkspaceRole.ADMIN)
    }
}
