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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** InternalTokenIssuer 의 토큰 캐싱(RS256 서명 분할상환) 동작 검증. */
class InternalTokenIssuerCacheTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun issuer(
        cacheEnabled: Boolean = true,
        ttlSeconds: Long = 60,
        refreshMarginSeconds: Long = 10,
    ) = InternalTokenIssuer(
        issuerName = "gateway",
        privateKey = keyPair.private as RSAPrivateKey,
        keyId = "test",
        ttlSeconds = ttlSeconds,
        cacheEnabled = cacheEnabled,
        refreshMarginSeconds = refreshMarginSeconds,
    )

    @AfterTest
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `same subject-audience-roles reuses the exact same signed token within window`() {
        val iss = issuer()
        val first = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        val second = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        assertEquals(first, second, "캐시 히트면 재서명 없이 동일 토큰이어야 한다")
    }

    @Test
    fun `roles order does not affect the cache key`() {
        val iss = issuer()
        val a = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER", "ADMIN"))
        val b = iss.issue(subject = "42", audience = "core-api", roles = listOf("ADMIN", "USER"))
        assertEquals(a, b, "roles 순서만 다르면 같은 캐시 엔트리여야 한다")
    }

    @Test
    fun `different subject or audience yields a different token`() {
        val iss = issuer()
        val base = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        assertNotEquals(base, iss.issue(subject = "43", audience = "core-api", roles = listOf("USER")))
        assertNotEquals(base, iss.issue(subject = "42", audience = "iam-api", roles = listOf("USER")))
        assertNotEquals(base, iss.issue(subject = "42", audience = "core-api", roles = listOf("ADMIN")))
    }

    @Test
    fun `caching disabled mints a fresh token every call`() {
        val iss = issuer(cacheEnabled = false)
        val first = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        val second = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        assertNotEquals(first, second, "캐시 비활성화면 매번 새로 서명(다른 jti)해야 한다")
    }

    @Test
    fun `token is re-signed once the reuse window passes`() {
        // reuseWindow = ttl - margin = 1s. 1.2s 뒤엔 재서명되어야 한다.
        val iss = issuer(ttlSeconds = 1, refreshMarginSeconds = 0)
        val first = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        Thread.sleep(1_200)
        val second = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        assertNotEquals(first, second, "재사용 윈도우가 지나면 새 토큰이어야 한다")
    }

    @Test
    fun `cached token still passes downstream verification`() {
        val iss = issuer()
        val token = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))
        // 두 번째(캐시 히트) 토큰으로도 검증 통과해야 한다.
        val cached = iss.issue(subject = "42", audience = "core-api", roles = listOf("USER"))

        val filter = InternalTokenAuthenticationFilter(
            expectedAudience = "core-api",
            knownIssuers = mapOf("gateway" to keyPair.public as RSAPublicKey),
            clockSkewSeconds = 30,
        )
        val request = MockHttpServletRequest().apply {
            addHeader(InternalTokenAuthenticationFilter.HEADER_NAME, cached)
        }
        val chain = CapturingChain()
        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertTrue(chain.captured is InternalAuthentication, "캐시 토큰도 유효해 SecurityContext 를 채워야 한다")
        assertEquals(token, cached)
    }

    private class CapturingChain : FilterChain {
        var captured: Authentication? = null
        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            captured = SecurityContextHolder.getContext().authentication
        }
    }
}
