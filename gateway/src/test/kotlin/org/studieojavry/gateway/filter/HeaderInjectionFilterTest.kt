package org.studieojavry.gateway.filter

import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter.Companion.HEADER_NAME
import org.studieojavry.internalauth.InternalTokenIssuer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey

/**
 * HeaderInjectionFilter 단위 테스트 — 운영 관점의 두 핵심 계약:
 *   1) 경로 → downstream aud 매핑. 틀리면 downstream 이 401 (aud 불일치). RouteConfig 와 정합해야 함.
 *   2) 신원 위조 방지: 인증된 요청은 gateway 서명 토큰이 주입되고, 클라이언트가 보낸
 *      X-Internal-Auth 는 덮어써진다. 미인증(permitAll) 요청엔 토큰이 주입되지 않는다.
 *
 * 실 InternalTokenIssuer(RSA) 를 써서 주입된 토큰을 디코드해 aud/sub 를 직접 검증한다(모킹 X).
 */
class HeaderInjectionFilterTest {

    private val rsaKey: RSAPrivateKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateKey
    private val issuer = InternalTokenIssuer("gateway", rsaKey, "gw-key", 60)
    private val filter = HeaderInjectionFilter(issuer)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(sub: String = "user-1", roles: List<String> = listOf("USER")) {
        val jwt = Jwt.withTokenValue("t").header("alg", "none")
            .subject(sub).claim("roles", roles).build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt, "n/a")
    }

    /** 필터를 태우고 downstream 으로 전달된 (wrapping 된) 요청을 반환. */
    private fun pass(uri: String, preHeaders: Map<String, String> = emptyMap()): HttpServletRequest {
        val req = MockHttpServletRequest().apply {
            requestURI = uri
            preHeaders.forEach { (k, v) -> addHeader(k, v) }
        }
        val chain = MockFilterChain()
        filter.doFilter(req, MockHttpServletResponse(), chain)
        return chain.request as HttpServletRequest
    }

    private fun audienceOf(uri: String): List<String> {
        authenticate()
        val token = pass(uri).getHeader(HEADER_NAME) ?: error("internal token not injected for $uri")
        return SignedJWT.parse(token).jwtClaimsSet.audience
    }

    @Test
    fun `경로별 downstream audience 매핑이 RouteConfig 와 정합`() {
        assertAll(
            { assertEquals(listOf("noti-api"), audienceOf("/api/v1/users/me/notification-settings")) },
            { assertEquals(listOf("noti-api"), audienceOf("/api/v1/users/me/notifications")) },
            { assertEquals(listOf("noti-api"), audienceOf("/api/v1/users/me/device-tokens")) },
            { assertEquals(listOf("insight-api"), audienceOf("/api/v1/users/me/activity-grass")) },
            { assertEquals(listOf("insight-api"), audienceOf("/api/v1/users/me/kpis")) },
            { assertEquals(listOf("publish-api"), audienceOf("/api/v1/publishments/42")) },
            { assertEquals(listOf("publish-api"), audienceOf("/p/abc123")) },
            { assertEquals(listOf("core-api"), audienceOf("/api/v1/error-cases")) },
            { assertEquals(listOf("core-api"), audienceOf("/api/v1/error-attachments/9")) },
            { assertEquals(listOf("core-api"), audienceOf("/api/v1/users/me/watchlist")) },
            { assertEquals(listOf("core-api"), audienceOf("/api/v1/users/me/following-feed")) },
            { assertEquals(listOf("core-api"), audienceOf("/api/v1/users/123/recent-activities")) },
            // 기본값(else) = iam-api. users/me 본체·인증 등.
            { assertEquals(listOf("iam-api"), audienceOf("/api/v1/users/me")) },
            { assertEquals(listOf("iam-api"), audienceOf("/api/v1/auth/login")) },
        )
    }

    @Test
    fun `인증된 요청은 sub·roles 가 담긴 gateway 토큰을 주입한다`() {
        authenticate(sub = "user-42", roles = listOf("USER", "ADMIN"))
        val token = pass("/api/v1/error-cases").getHeader(HEADER_NAME)
        assertNotNullToken(token)
        val claims = SignedJWT.parse(token).jwtClaimsSet
        assertEquals("user-42", claims.subject)
        assertEquals("gateway", claims.issuer)
        assertEquals(listOf("core-api"), claims.audience)
    }

    @Test
    fun `미인증(permitAll) 요청엔 internal 토큰이 주입되지 않는다`() {
        // authenticate() 호출 안 함 → SecurityContext 에 Jwt 없음
        val passed = pass("/api/v1/auth/login")
        assertNull(passed.getHeader(HEADER_NAME))
    }

    @Test
    fun `인증되면 클라이언트가 보낸 X-Internal-Auth 를 gateway 토큰으로 덮어쓴다`() {
        authenticate(sub = "real-user")
        val token = pass("/api/v1/error-cases", mapOf(HEADER_NAME to "forged.header.value")).getHeader(HEADER_NAME)
        assertNotEquals("forged.header.value", token)
        assertEquals("real-user", SignedJWT.parse(token).jwtClaimsSet.subject)
    }

    private fun assertNotNullToken(token: String?) {
        assertNotEquals(null, token, "internal token 이 주입되어야 함")
    }
}
