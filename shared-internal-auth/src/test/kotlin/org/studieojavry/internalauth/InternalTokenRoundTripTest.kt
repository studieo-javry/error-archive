package org.studieojavry.internalauth

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InternalTokenRoundTripTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val issuer = InternalTokenIssuer(
        issuerName = "gateway",
        privateKey = keyPair.private as RSAPrivateKey,
        keyId = "test",
        ttlSeconds = 60,
    )

    private fun filter(audience: String) = InternalTokenAuthenticationFilter(
        expectedAudience = audience,
        knownIssuers = mapOf("gateway" to keyPair.public as RSAPublicKey),
        clockSkewSeconds = 30,
    )

    /** chain 호출 시점(= 필터가 context 를 채운 직후, finally 로 clear 되기 전)에 authentication 을 캡처. */
    private class CapturingChain : FilterChain {
        var captured: Authentication? = null
        var invoked = false
        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            invoked = true
            captured = SecurityContextHolder.getContext().authentication
        }
    }

    @AfterTest
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `valid token populates SecurityContext with userId principal`() {
        val token = issuer.issue(subject = "12345", audience = "core-api", roles = listOf("USER"))
        val request = MockHttpServletRequest().apply {
            addHeader(InternalTokenAuthenticationFilter.HEADER_NAME, token)
        }
        val chain = CapturingChain()

        filter("core-api").doFilter(request, MockHttpServletResponse(), chain)

        val auth = chain.captured
        assertTrue(auth is InternalAuthentication)
        assertEquals(12345L, auth.principal)
        assertEquals("gateway", auth.callerService)
        assertTrue(auth.authorities.any { it.authority == "ROLE_USER" })
    }

    @Test
    fun `wrong audience is rejected with 401`() {
        val token = issuer.issue(subject = "12345", audience = "iam-api", roles = emptyList())
        val request = MockHttpServletRequest().apply {
            addHeader(InternalTokenAuthenticationFilter.HEADER_NAME, token)
        }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter("core-api").doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(chain.captured)
    }

    @Test
    fun `missing header passes through without authentication`() {
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter("core-api").doFilter(MockHttpServletRequest(), response, chain)

        assertTrue(chain.invoked)
        assertEquals(200, response.status)
        assertNull(chain.captured)
    }
}
