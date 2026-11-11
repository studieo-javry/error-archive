package org.studieojavry.coreapi.shared.config

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.studieojavry.sharederror.security.AuthFailureMonitor
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 회귀: core-api 는 oauth2-resource-server 를 런타임에 두지 않는다(인증은 게이트웨이가 주입한
 * 내부 JWT 전용 — `InternalTokenAuthenticationFilter`). 과거 공용
 * [ProblemDetailAuthenticationEntryPoint] 가 `InvalidBearerTokenException` 을 **직접 타입 참조**해,
 * 미인증 요청 처리 중 그 부재(不在) 클래스를 로드하려다 NoClassDefFoundError 가 났고, 그 결과
 * 게이트웨이를 거치지 않고 core 를 직접 친 미인증 요청이 401 대신 500 을 받았다.
 *
 * 이 테스트는 oauth2 가 **없는** core-api 테스트 클래스패스에서 돌아(= 운영 core 와 동일한 조건),
 * 엔트리포인트가 oauth2 클래스를 로드하지 않고 깔끔하게 401 MISSING_TOKEN 을 내는지 지킨다.
 * (구버전 엔트리포인트였다면 이 테스트는 NoClassDefFoundError 로 실패한다.)
 */
class AuthEntryPointClasspathRegressionTest {

    private val entryPoint = ProblemDetailAuthenticationEntryPoint(
        JsonMapper.builder().build(),
        TraceIdAccessor(null),
        object : AuthFailureMonitor { override fun recordFailure(ip: String): Int = 0 },
    )

    @Test
    fun `미인증 요청은 oauth2 클래스 로드 없이 401 MISSING_TOKEN 을 낸다`() {
        val res = MockHttpServletResponse()

        entryPoint.commence(
            MockHttpServletRequest("GET", "/api/v1/agent/me"),
            res,
            BadCredentialsException("no internal token"),
        )

        assertEquals(401, res.status)
        assertTrue(res.contentType!!.contains("problem+json"), "content-type=${res.contentType}")
        assertTrue(
            res.contentAsString.contains("\"code\":\"MISSING_TOKEN\""),
            "body=${res.contentAsString}",
        )
    }
}
