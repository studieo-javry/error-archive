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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.coreapi.demo.application.DemoResetService
import org.studieojavry.coreapi.demo.application.DemoSeeder
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.internalauth.InternalAuthentication

/**
 * 게스트 사용 가이드 대상 조회(`GET /api/v1/demo/guide`) — @SpringBootTest + H2 + MockMvc.
 * 데모 identity(iam HTTP) 만 목킹. 리셋으로 실제 시드를 깐 뒤, 오늘의 가이드 케이스 id 와 고정 slug 를 내려주는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true", "demo.seed-attachments=false", "demo.guide-article-slug=guide-test-slug"])
class DemoGuideControllerTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var resetService: DemoResetService
    @Autowired private lateinit var errorCaseRepository: ErrorCaseRepositoryPort

    @MockitoBean private lateinit var identityClient: DemoIamIdentityClient

    private val wsId = 9101L
    private val botId = 11L

    private fun mvc(): MockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity()).build()

    private val guest = authentication(
        InternalAuthentication(5100L, listOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_GUEST")), "gateway"),
    )

    @Test
    fun `게스트는 오늘의 가이드 케이스 id 와 아티클 slug 를 받는다 — 리셋 후엔 새 id`() {
        Mockito.`when`(identityClient.fetchIdentity())
            .thenReturn(DemoIamIdentityClient.DemoIdentity(enabled = true, workspaceId = wsId, botUserId = botId))
        resetService.reset()

        val guideId = guideCaseId()
        mvc().perform(get("/api/v1/demo/guide").with(guest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(guideId))
            .andExpect(jsonPath("$.articleSlug").value("guide-test-slug"))

        // 야간 리셋(wipe+reseed) → 케이스 id 가 바뀌어도 새 id 를 따라간다.
        resetService.reset()
        val newId = guideCaseId()
        assertThat(newId).isNotEqualTo(guideId)
        mvc().perform(get("/api/v1/demo/guide").with(guest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(newId))
    }

    @Test
    fun `데모 identity 를 못 얻으면 404`() {
        Mockito.`when`(identityClient.fetchIdentity()).thenReturn(null)
        mvc().perform(get("/api/v1/demo/guide").with(guest))
            .andExpect(status().isNotFound)
    }

    private fun guideCaseId(): Long = errorCaseRepository.search(
        ErrorCaseSearchCriteria(
            workspaceId = wsId, ownerUserId = botId, status = null, fingerprint = null,
            visibility = null, cursorCreatedAt = null, cursorId = null, limit = 100,
        ),
    ).single { it.title == DemoSeeder.GUIDE_CASE_TITLE }.id
}
