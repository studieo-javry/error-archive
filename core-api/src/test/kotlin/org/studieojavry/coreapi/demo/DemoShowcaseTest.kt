package org.studieojavry.coreapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
import org.studieojavry.coreapi.errorcase.case.application.usecase.SearchPublicErrorCasesUseCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.internalauth.InternalAuthentication
import java.time.LocalDateTime

/**
 * 공개 쇼케이스(회원 Search 용 고정 데모 케이스) + 게스트 쓰기 범위 제한 검증 — @SpringBootTest + H2 + MockMvc.
 * 데모 identity(iam) 와 워크스페이스 역할 조회만 목킹.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true", "demo.seed-attachments=false"])
class DemoShowcaseTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var resetService: DemoResetService
    @Autowired private lateinit var seeder: DemoSeeder
    @Autowired private lateinit var errorCaseRepository: ErrorCaseRepositoryPort
    @Autowired private lateinit var searchPublic: SearchPublicErrorCasesUseCase

    @MockitoBean private lateinit var identityClient: DemoIamIdentityClient
    @MockitoBean private lateinit var workspaceQuery: WorkspaceQueryPort

    private val wsId = 9101L
    private val personas = listOf(11L, 12L, 13L)
    private val memberId = 7L
    private val guestId = 5000L

    @BeforeEach
    fun identity() {
        Mockito.`when`(identityClient.fetchIdentity()).thenReturn(
            DemoIamIdentityClient.DemoIdentity(enabled = true, workspaceId = wsId, botUserId = 1L, personaUserIds = personas),
        )
        Mockito.`when`(workspaceQuery.getViewerRole(anyLong(), anyLong()))
            .thenReturn(WorkspaceQueryPort.WorkspaceRole.WRITE)
    }

    private fun showcase() = errorCaseRepository.findDemoShowcase()

    private fun publicSearchIds() = searchPublic.invoke(
        SearchPublicErrorCasesUseCase.Input(status = null, fingerprint = null, cursor = null, size = 100, viewerUserId = null),
    ).items.map { it.id }

    private fun mvc(): MockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity()).build()

    private fun MockHttpServletRequestBuilder.asGuest() =
        with(authentication(InternalAuthentication(guestId, listOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_GUEST")), "gateway")))

    private fun MockHttpServletRequestBuilder.asMember() =
        with(authentication(InternalAuthentication(memberId, emptyList(), "gateway")))

    private fun comment(caseId: Long) = post("/api/v1/error-cases/$caseId/comments")
        .contentType(MediaType.APPLICATION_JSON).content("""{"body":"좋은 정리네요"}""")

    private fun createCase(workspaceId: Long?) = post("/api/v1/error-cases")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"title":"guest case","description":null,"workspaceId":${workspaceId ?: "null"},"occurredAt":null,"visibility":"${if (workspaceId == null) "PUBLIC" else "WORKSPACE"}"}""")

    private fun ensureShowcase(): List<Long> {
        resetService.syncShowcase(personas)
        return showcase().keys.toList()
    }

    @Test
    fun `쇼케이스는 공개·워크스페이스 없음·데모 표식으로 회원 검색에 나온다`() {
        val ids = ensureShowcase()
        assertThat(ids).isNotEmpty()

        val summaries = errorCaseRepository.findSummariesByIds(ids)
        assertThat(summaries).allMatch {
            it.visibility == Visibility.PUBLIC && it.workspaceId == null && it.demo && it.ownerUserId in personas
        }
        // 내부 버전 태그(seed-vN)는 회원 카드에 보이지 않게 붙이지 않는다
        assertThat(summaries.flatMap { it.tags }).noneMatch { it.startsWith("seed-v") }
        assertThat(publicSearchIds()).containsAll(ids)

        // 목록·상세 응답에 demo=true
        mvc().perform(get("/api/v1/error-cases/${ids.first()}").asMember())
            .andExpect(status().isOk).andExpect(jsonPath("$.demo").value(true))
    }

    @Test
    fun `쇼케이스는 고정 — 재기동·야간 리셋에도 그대로, 버전이 바뀔 때만 다시 만든다`() {
        val ids = ensureShowcase().toSet()

        assertThat(resetService.syncShowcase(personas)).isZero()
        resetService.reset()                       // 야간 리셋은 데모 WS 만
        resetService.syncSeedOnStartup()
        assertThat(showcase().keys).isEqualTo(ids)

        // 옛 버전 표식 → 다음 기동 때 교체
        errorCaseRepository.markDemo(ids, seeder.showcaseVersion - 1)
        assertThat(resetService.syncShowcase(personas)).isEqualTo(ids.size)
        val after = showcase()
        assertThat(after.keys).doesNotContainAnyElementsOf(ids).hasSize(ids.size)
        assertThat(after.values).allMatch { it == seeder.showcaseVersion }
        assertThat(errorCaseRepository.findSummariesByIds(ids)).isEmpty()
    }

    @Test
    fun `데모 WS 시드도 데모 표식, 회원 케이스와 일반 수정은 표식에 영향 없음`() {
        resetService.reset()
        val wsCases = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = wsId, ownerUserId = null, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = 100,
            ),
        )
        assertThat(wsCases).isNotEmpty().allMatch { it.demo }

        // 도메인 저장(update) 이 표식을 지우지 않는다
        val id = ensureShowcase().first()
        val case = errorCaseRepository.findById(id)!!
        assertThat(case.demo).isTrue()
        case.title = "${case.title} (edited)"
        errorCaseRepository.update(case)
        assertThat(errorCaseRepository.findById(id)!!.demo).isTrue()

        // 회원이 만든 케이스는 데모 아님
        val created = mvc().perform(createCase(null).asMember()).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val memberCaseId = Regex(""""id":(\d+)""").find(created)!!.groupValues[1].toLong()
        assertThat(errorCaseRepository.findById(memberCaseId)!!.demo).isFalse()
    }

    @Test
    fun `추천 작성자 후보에서 데모 persona 를 뺀다`() {
        ensureShowcase()
        val owners = errorCaseRepository.findTopOwnersOfRecentPublic(LocalDateTime.now().minusYears(1), 100)
        assertThat(owners).doesNotContainAnyElementsOf(personas)
    }

    @Test
    fun `게스트는 데모 WS 케이스에만 쓸 수 있다 — 쇼케이스·개인 공개 생성은 403`() {
        val showcaseId = ensureShowcase().first()
        resetService.reset()
        val wsCaseId = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = wsId, ownerUserId = null, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = 1,
            ),
        ).first().id
        val mvc = mvc()

        // 게스트: 쇼케이스(공개) 댓글·me-too 403, 데모 WS 케이스 댓글은 허용
        mvc.perform(comment(showcaseId).asGuest()).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/error-cases/$showcaseId/me-too").asGuest()).andExpect(status().isForbidden)
        mvc.perform(comment(wsCaseId).asGuest()).andExpect(status().isCreated)

        // 게스트: 개인(공개) 케이스 생성 403, 데모 WS 생성 허용, GitHub Issue 가져오기 403
        mvc.perform(createCase(null).asGuest()).andExpect(status().isForbidden)
        mvc.perform(createCase(wsId).asGuest()).andExpect(status().isCreated)
        mvc.perform(
            post("/api/v1/error-cases/from-github-issue").contentType(MediaType.APPLICATION_JSON).content("{}").asGuest(),
        ).andExpect(status().isForbidden)

        // 읽기는 막지 않는다
        mvc.perform(get("/api/v1/error-cases/$showcaseId").asGuest()).andExpect(status().isOk)

        // 회원: 쇼케이스에 댓글 가능(고정 데이터라 남는다)
        mvc.perform(comment(showcaseId).asMember()).andExpect(status().isCreated)
    }

    @Test
    fun `데모 WS 를 모르면(iam 장애) 게스트 쓰기는 거부된다`() {
        val showcaseId = ensureShowcase().first()
        Mockito.`when`(identityClient.fetchIdentity()).thenReturn(null)
        // 캐시가 남아 있어도 다른 WS 케이스는 어차피 거부 — 생성(개인) 경로로 fail-closed 확인
        mvc().perform(createCase(null).asGuest()).andExpect(status().isForbidden)
        mvc().perform(comment(showcaseId).asGuest()).andExpect(status().isForbidden)
    }
}
