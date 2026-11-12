package org.studieojavry.sharederror.security

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ProblemDetailAuthenticationEntryPoint] 의 401 분류 회귀 테스트.
 *
 * - bearer 토큰 예외는 사유별 code 로 분류(TOKEN_EXPIRED / INVALID_TOKEN_SIGNATURE / ...).
 * - bearer 가 아닌(= 토큰 부재 등) 인증 실패는 MISSING_TOKEN.
 *
 * 핵심: InvalidBearerTokenException 판별은 **이름 기반**이라, oauth2-resource-server 가 런타임에
 * 없는 소비 서비스에서도 안전하다(그 회귀는 core-api 쪽 테스트가 oauth2 부재 클래스패스에서 지킨다).
 */
class ProblemDetailAuthenticationEntryPointTest {

    private val entryPoint = ProblemDetailAuthenticationEntryPoint(
        JsonMapper.builder().build(),
        TraceIdAccessor(null),
        object : AuthFailureMonitor { override fun recordFailure(ip: String): Int = 0 },
    )

    private fun codeOf(ex: AuthenticationException): String {
        val res = MockHttpServletResponse()
        entryPoint.commence(MockHttpServletRequest("GET", "/api/v1/agent/me"), res, ex)
        assertEquals(401, res.status)
        assertTrue(res.contentType!!.contains("problem+json"), "content-type=${res.contentType}")
        return Regex("\"code\":\"([A-Z_]+)\"").find(res.contentAsString)?.groupValues?.get(1)
            ?: error("응답에 code 없음: ${res.contentAsString}")
    }

    @Test
    fun `토큰 부재 등 bearer 가 아닌 인증 실패는 MISSING_TOKEN`() {
        assertEquals(SecurityErrorCodes.MISSING_TOKEN, codeOf(BadCredentialsException("no token")))
    }

    @Test
    fun `만료된 bearer 토큰은 TOKEN_EXPIRED`() {
        assertEquals(SecurityErrorCodes.TOKEN_EXPIRED, codeOf(InvalidBearerTokenException("Jwt expired at 2020-01-01T00:00:00Z")))
    }

    @Test
    fun `서명 불일치 bearer 토큰은 INVALID_TOKEN_SIGNATURE`() {
        assertEquals(SecurityErrorCodes.INVALID_TOKEN_SIGNATURE, codeOf(InvalidBearerTokenException("Invalid signature for token")))
    }

    @Test
    fun `audience 불일치 bearer 토큰은 INVALID_TOKEN_AUDIENCE`() {
        assertEquals(SecurityErrorCodes.INVALID_TOKEN_AUDIENCE, codeOf(InvalidBearerTokenException("The aud claim is not valid")))
    }

    @Test
    fun `그 외 bearer 토큰 오류는 INVALID_TOKEN`() {
        assertEquals(SecurityErrorCodes.INVALID_TOKEN, codeOf(InvalidBearerTokenException("malformed token")))
    }
}
