package org.studieojavry.iamapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.command.SocialLoginCommand
import org.studieojavry.iamapi.auth.application.port.SocialIdentityRepositoryPort
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.ProviderCredential
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.SocialProfile
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.application.usecase.SocialLoginUseCase
import org.studieojavry.iamapi.auth.domain.model.SocialIdentity
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import org.studieojavry.iamapi.demo.application.DemoIdentityConflictException
import org.studieojavry.iamapi.demo.application.DemoWorkspaceProvisioner
import org.studieojavry.iamapi.demo.application.GuestLoginUseCase
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.command.CreateWorkspaceCommand
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.application.usecase.CreateWorkspaceUseCase
import org.studieojavry.iamapi.workspace.application.usecase.CreateWorkspaceUseCase.DuplicateSlugException

/**
 * 데모 이름(handle `demo-*`, slug `demo`)을 회원이 차지해 데모로 오인되는 경로를 막는지 검증.
 *  - 회원은 예약 이름을 가질 수 없다(가입 handle, 워크스페이스 slug).
 *  - 이미 회원이 그 이름을 가졌다면 데모 provisioning 이 멈춘다 → core 리셋·게스트 로그인 중단(fail-closed).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true"])
@Import(DemoNameGuardTest.FakeFetcherConfig::class)
@Transactional
class DemoNameGuardTest(
    @Autowired private val socialLogin: SocialLoginUseCase,
    @Autowired private val createWorkspace: CreateWorkspaceUseCase,
    @Autowired private val provisioner: DemoWorkspaceProvisioner,
    @Autowired private val guestLogin: GuestLoginUseCase,
    @Autowired private val userRepository: UserRepositoryPort,
    @Autowired private val socialIdentityRepository: SocialIdentityRepositoryPort,
    @Autowired private val workspaceRepository: WorkspaceRepositoryPort,
    @Autowired private val memberRepository: WorkspaceMemberRepositoryPort,
    @Autowired private val props: DemoProperties,
) {

    @Test
    fun `GitHub 아이디가 데모 이름이어도 회원 handle 은 user- 접두로 바뀐다`() {
        listOf("demo-guest", "demo-bot", "Demo").forEach { login ->
            val r = socialLogin.invoke(loginAs(login))
            val handle = userRepository.findById(r.userId)!!.handle
            assertThat(handle).isEqualTo("user-${login.lowercase()}")
        }
        // 데모 계정은 그대로 데모 계정 — 회원 가입이 데모 이름을 가져가지 않았다
        assertThat(provisioner.ensureProvisioned().botUserId).isNotNull()
    }

    @Test
    fun `데모와 무관한 비슷한 이름은 그대로 쓴다`() {
        val r = socialLogin.invoke(loginAs("demolition"))
        assertThat(userRepository.findById(r.userId)!!.handle).isEqualTo("demolition")
    }

    @Test
    fun `회원은 데모 예약 slug 로 워크스페이스를 만들 수 없다`() {
        val member = signUp("ws-owner")
        listOf("demo", "demo-team").forEach { slug ->
            assertThatThrownBy { createWorkspace.invoke(CreateWorkspaceCommand(member, "x", slug)) }
                .isInstanceOf(DuplicateSlugException::class.java)
        }
        assertThat(createWorkspace.invoke(CreateWorkspaceCommand(member, "x", "demolab")).slug).isEqualTo("demolab")
    }

    @Test
    fun `데모 봇 handle 이 회원 계정이면 provisioning 이 멈춘다`() {
        val bot = userRepository.findByHandle(props.botHandle)!!
        socialIdentityRepository.save(SocialIdentity.link(bot.id!!, SocialProvider.GITHUB, "gh-bot", null, null))

        assertThatThrownBy { provisioner.ensureProvisioned() }
            .isInstanceOf(DemoIdentityConflictException::class.java)
    }

    @Test
    fun `데모 slug 워크스페이스를 회원이 만들었으면 provisioning 이 멈춘다`() {
        val member = signUp("early-bird")
        // 예약 이전에 회원이 만들어 둔 상황 — 다른 slug 를 데모 slug 로 쓰는 provisioner 로 재현
        val ws = createWorkspace.invoke(CreateWorkspaceCommand(member, "Member Team", "member-team"))
        val hijacked = DemoWorkspaceProvisioner(
            userRepository, socialIdentityRepository, workspaceRepository, memberRepository,
            props.copy(workspaceSlug = "member-team"),
        )

        assertThatThrownBy { hijacked.ensureProvisioned() }
            .isInstanceOf(DemoIdentityConflictException::class.java)
        // 회원 워크스페이스에 데모 봇이 끼어들지 않았다
        val bot = userRepository.findByHandle(props.botHandle)!!
        assertThat(memberRepository.findByWorkspaceIdAndUserId(ws.workspaceId, bot.id!!)).isNull()
    }

    @Test
    fun `공유 게스트 handle 이 회원 계정이면 게스트 로그인이 멈춘다`() {
        guestLogin.invoke()   // 공유 게스트 생성
        val guest = userRepository.findByHandle(props.guestHandle)!!
        socialIdentityRepository.save(SocialIdentity.link(guest.id!!, SocialProvider.GITHUB, "gh-guest", null, null))

        assertThatThrownBy { guestLogin.invoke() }
            .isInstanceOf(DemoIdentityConflictException::class.java)
    }

    private fun signUp(login: String): Long = socialLogin.invoke(loginAs(login)).userId

    private fun loginAs(login: String) = SocialLoginCommand(
        provider = SocialProvider.APPLE,
        credential = ProviderCredential.IdToken(login),
        rememberMe = false,
    )

    /** APPLE 자격증명(idToken) 을 그대로 GitHub login 처럼 돌려주는 가짜 fetcher. */
    @TestConfiguration
    class FakeFetcherConfig {
        @Bean
        fun fakeFetcher(): SocialProfileFetcherPort = object : SocialProfileFetcherPort {
            override fun supports(provider: SocialProvider) = provider == SocialProvider.APPLE
            override fun fetch(credential: ProviderCredential): SocialProfile {
                val login = (credential as ProviderCredential.IdToken).idToken
                return SocialProfile(
                    providerUserId = "fake-$login", email = null, providerLogin = login,
                    displayName = login, profileUrl = null, avatarUrl = null,
                )
            }
        }
    }
}
