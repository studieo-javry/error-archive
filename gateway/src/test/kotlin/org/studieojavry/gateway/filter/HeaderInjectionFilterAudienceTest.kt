package org.studieojavry.gateway.filter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import kotlin.test.assertTrue

/**
 * [HeaderInjectionFilter] 가 요청 경로로 내부 JWT 의 audience(=downstream 서비스)를 정확히 고르는지 검증.
 *
 * ⚠️ 회귀 방지: core-api 가 서버인 경로를 RouteConfig(coreApiRoute)에만 추가하고 여기 resolveAudience 에
 * 빠뜨리면, aud=iam-api 로 발급돼 core-api 가 401 을 낸다(게이트웨이 라우트는 통과하는데 인증만 깨지는
 * 추적 어려운 버그). `/api/v1/captures`(자동 수집 초안 검토) 가 바로 그렇게 누락됐던 경로.
 */
class HeaderInjectionFilterAudienceTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val issuer = InternalTokenIssuer(
        issuerName = "gateway",
        privateKey = keyPair.private as RSAPrivateKey,
        keyId = "test",
        ttlSeconds = 60,
        cacheEnabled = false,
    )
    private val filter = HeaderInjectionFilter(issuer)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    /** 주어진 경로로 필터를 태우고, 주입된 내부 JWT 의 payload(디코드된 JSON 문자열)를 돌려준다. */
    private fun audiencePayloadFor(uri: String): String {
        val jwt = Jwt.withTokenValue("user-token").header("alg", "none")
            .subject("1").claim("roles", listOf("USER")).build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt, "n/a")

        val chain = MockFilterChain()
        filter.doFilter(MockHttpServletRequest("GET", uri), MockHttpServletResponse(), chain)

        val forwarded = chain.request as jakarta.servlet.http.HttpServletRequest
        val token = forwarded.getHeader(InternalTokenAuthenticationFilter.HEADER_NAME)
            ?: error("내부 JWT 헤더가 주입되지 않음(경로=$uri)")
        val payload = token.split(".")[1]
        return String(Base64.getUrlDecoder().decode(payload))
    }

    @Test
    fun `검토 경로 api v1 captures 는 core-api 로 발급된다`() {
        assertTrue(audiencePayloadFor("/api/v1/captures").contains("core-api"), "큐 경로 aud=core-api")
        assertTrue(
            audiencePayloadFor("/api/v1/captures/1565cc34-be00-4f18-9cfe-04adc09b3c96").contains("core-api"),
            "상세 경로 aud=core-api",
        )
    }

    @Test
    fun `기존 core-api 경로 error-cases 도 core-api (정합 sanity)`() {
        assertTrue(audiencePayloadFor("/api/v1/error-cases").contains("core-api"))
    }

    @Test
    fun `매핑되지 않은 경로는 기본 iam-api`() {
        assertTrue(audiencePayloadFor("/api/v1/users/me/profile").contains("iam-api"))
    }
}
