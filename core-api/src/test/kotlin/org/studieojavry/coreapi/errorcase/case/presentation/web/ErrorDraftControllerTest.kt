package org.studieojavry.coreapi.errorcase.case.presentation.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.internalauth.InternalAuthentication

/**
 * `POST /api/v1/error-cases/draft-suggestion` HTTP 계약 — @SpringBootTest + MockMvc.
 *
 * LLM 은 기본 비활성(llm.enabled 미설정)이라 NoOp → **정규식 폴백**으로 동작한다(결정적·오프라인).
 * 따라서 예외/제목은 정규식이 채우고 severity 는 비며 `degraded=true` 가 나와야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ErrorDraftControllerTest {

    @Autowired private lateinit var context: WebApplicationContext

    private fun mvc(): MockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity()).build()

    private val user = authentication(
        InternalAuthentication(42L, emptyList<GrantedAuthority>(), "gateway"),
    )

    @Test
    fun `붙여넣기 → 정규식 폴백 초안(200, degraded=true, 예외 추출)`() {
        val body = """
            {"paste":"java.lang.NullPointerException: coupon is null\n\tat com.shop.OrderService.calculateTotal(OrderService.java:42)"}
        """.trimIndent()

        mvc().perform(
            post("/api/v1/error-cases/draft-suggestion").with(user)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.exceptionClass").value("java.lang.NullPointerException"))
            .andExpect(jsonPath("$.title").isNotEmpty)
            .andExpect(jsonPath("$.degraded").value(true))
            .andExpect(jsonPath("$.severity").doesNotExist())
    }

    @Test
    fun `paste 가 공백이면 400`() {
        mvc().perform(
            post("/api/v1/error-cases/draft-suggestion").with(user)
                .contentType(MediaType.APPLICATION_JSON).content("""{"paste":"  "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    // 미인증(401)은 전역 보안설정(anyRequest().authenticated())이 보장하는 부분이라
    // 이 컨트롤러 단위에서 중복 검증하지 않는다(기존 컨트롤러 테스트들도 항상 인증 상태로만 호출).
}
