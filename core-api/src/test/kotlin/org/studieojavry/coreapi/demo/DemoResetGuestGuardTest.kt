package org.studieojavry.coreapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.demo.application.DemoResetService
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.internalauth.InternalAuthentication

/**
 * 데모 리셋(wipe+reseed) + 게스트 업로드 가드 검증 — @SpringBootTest + H2(실 JPA) + MockMvc.
 * 데모 identity(iam HTTP) 만 목킹, 나머지는 실제 스택.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true"])
class DemoResetGuestGuardTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var resetService: DemoResetService
    @Autowired private lateinit var errorCaseRepository: ErrorCaseRepositoryPort

    @MockitoBean private lateinit var identityClient: DemoIamIdentityClient

    private val wsId = 9001L
    private val botId = 1L

    private fun searchWs(limit: Int) = errorCaseRepository.search(
        ErrorCaseSearchCriteria(
            workspaceId = wsId, ownerUserId = null, status = null, fingerprint = null,
            visibility = null, cursorCreatedAt = null, cursorId = null, limit = limit,
        ),
    )

    @Test
    fun `reset 는 데모 WS 를 wipe 후 봇 케이스를 reseed 한다`() {
        Mockito.`when`(identityClient.fetchIdentity())
            .thenReturn(DemoIamIdentityClient.DemoIdentity(enabled = true, workspaceId = wsId, botUserId = botId))

        val r1 = resetService.reset()
        assertThat(r1.ran).isTrue()
        assertThat(r1.seeded).isGreaterThan(0)

        val after1 = searchWs(100)
        assertThat(after1).hasSize(r1.seeded)
        assertThat(after1).allMatch {
            it.ownerUserId == botId && it.visibility == Visibility.WORKSPACE && it.workspaceId == wsId
        }

        // 재리셋 → 직전 시드 전부 wipe + 동일 수 reseed (누적 없음)
        val r2 = resetService.reset()
        assertThat(r2.wiped).isEqualTo(r1.seeded)
        assertThat(r2.seeded).isEqualTo(r1.seeded)
        assertThat(searchWs(100)).hasSize(r1.seeded)
    }

    @Test
    fun `게스트는 첨부 업로드가 403, 일반 사용자는 403 이 아니다`() {
        val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()

        // 게스트(ROLE_GUEST) → 보안 필터에서 403
        mvc.perform(
            post("/api/v1/error-attachments")
                .with(authentication(InternalAuthentication(5000L, listOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_GUEST")), "gateway"))),
        ).andExpect(status().isForbidden)

        // 일반 사용자 → 403 아님(멀티파트 없음이라 415/400 등이지만 인가 자체는 통과)
        val normalStatus = mvc.perform(
            post("/api/v1/error-attachments")
                .with(authentication(InternalAuthentication(7L, emptyList(), "gateway"))),
        ).andReturn().response.status
        assertThat(normalStatus).isNotEqualTo(403)
    }
}
