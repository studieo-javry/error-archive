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
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.demo.application.DemoResetService
import org.studieojavry.iamapi.demo.application.DemoWorkspaceProvisioner
import org.studieojavry.iamapi.demo.application.GuestLoginUseCase
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

/**
 * "로그인 없이 둘러보기" 핵심 흐름 검증(H2 in-memory, 실제 Spring context).
 *  - 게스트 로그인: **고정 공유 게스트**(isGuest=true) + 데모 WS WRITE 가입 + roles=["GUEST"] 토큰
 *  - 재방문: 방문자는 같은 공유 게스트를 재사용(새 유저 생성 X)
 *  - 야간 리셋: 공유 게스트는 보존, 레거시 per-visitor 게스트만 삭제, 데모 봇/WS 보존
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true"])
@Transactional   // 테스트별 롤백으로 격리(데모 봇/WS 는 기동 시 커밋돼 유지)
class GuestDemoFlowTest(
    @Autowired private val guestLogin: GuestLoginUseCase,
    @Autowired private val resetService: DemoResetService,
    @Autowired private val provisioner: DemoWorkspaceProvisioner,
    @Autowired private val userRepository: UserRepositoryPort,
    @Autowired private val workspaceRepository: WorkspaceRepositoryPort,
    @Autowired private val memberRepository: WorkspaceMemberRepositoryPort,
    @Autowired private val props: DemoProperties,
) {

    @Test
    fun `게스트 로그인은 공유 게스트로 WRITE 멤버 + GUEST 토큰을 발급한다`() {
        val r = guestLogin.invoke()

        val user = userRepository.findById(r.userId)
        assertThat(user).isNotNull
        assertThat(user!!.isGuest).isTrue()
        assertThat(user.handle).isEqualTo(props.guestHandle)
        assertThat(r.displayName).isEqualTo(props.guestDisplayName)

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
    fun `재방문은 같은 공유 게스트를 재사용한다`() {
        val a = guestLogin.invoke()
        val b = guestLogin.invoke()

        // 새 유저를 만들지 않고 동일 공유 게스트를 재사용
        assertThat(a.userId).isEqualTo(b.userId)
        assertThat(a.workspaceId).isEqualTo(b.workspaceId)
        // 게스트 유저는 공유 하나뿐
        assertThat(userRepository.findGuests(100)).hasSize(1)
    }

    @Test
    fun `리셋은 공유 게스트를 보존하고 레거시 게스트만 지운다`() {
        // 공유 게스트 + 레거시 per-visitor 게스트(과거 방식 산물) 모의
        val shared = guestLogin.invoke()
        val demo = provisioner.ensureProvisioned()
        val legacy = userRepository.save(
            User.createGuest(handle = "guest-legacy-001", displayName = "게스트-OLD")
                .also { it.changeDefaultWorkspace(demo.workspaceId) },
        )
        memberRepository.save(WorkspaceMember.join(demo.workspaceId, legacy.id!!, WorkspaceRole.WRITE))
        assertThat(userRepository.findGuests(100)).hasSize(2)

        val removed = resetService.resetGuests()

        // 레거시만 삭제, 공유 게스트는 보존
        assertThat(removed).isEqualTo(1)
        val remaining = userRepository.findGuests(100)
        assertThat(remaining.map { it.handle }).containsExactly(props.guestHandle)
        assertThat(userRepository.findByHandle(props.guestHandle)!!.id).isEqualTo(shared.userId)
        assertThat(userRepository.findById(legacy.id!!)).isNull()

        // 데모 봇·워크스페이스·봇 멤버십은 그대로
        assertThat(userRepository.findByHandle("demo-bot")).isNotNull
        assertThat(workspaceRepository.findBySlug("demo")).isNotNull
        val botMember = memberRepository.findByWorkspaceIdAndUserId(demo.workspaceId, demo.botUserId)
        assertThat(botMember!!.role).isEqualTo(WorkspaceRole.ADMIN)
    }
}
