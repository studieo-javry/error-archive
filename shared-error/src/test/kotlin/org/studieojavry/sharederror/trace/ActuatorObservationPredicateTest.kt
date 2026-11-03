package org.studieojavry.sharederror.trace

import io.micrometer.observation.Observation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActuatorObservationPredicateTest {

    private val predicate = ActuatorObservationPredicate()

    @AfterEach
    fun clear() = RequestContextHolder.resetRequestAttributes()

    private fun serverContext(uri: String) =
        ServerRequestObservationContext(MockHttpServletRequest("GET", uri), MockHttpServletResponse())

    @Test
    fun `헬스체크·메트릭 수집 요청은 관측하지 않음 - prod·local 경로 둘 다`() {
        assertFalse(predicate.test("http.server.requests", serverContext("/internal/actuator/health/readiness")))
        assertFalse(predicate.test("http.server.requests", serverContext("/internal/actuator/prometheus")))
        assertFalse(predicate.test("http.server.requests", serverContext("/actuator/health")))
    }

    @Test
    fun `일반 API 요청은 관측함`() {
        assertTrue(predicate.test("http.server.requests", serverContext("/api/v1/error-cases/1")))
        assertTrue(predicate.test("http.server.requests", serverContext("/internal/users/7/following-ids")))
    }

    @Test
    fun `헬스체크 처리 중의 하위 관측(Security 필터 등)도 끔, 일반 요청 중엔 그대로`() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest("GET", "/internal/actuator/health/liveness")))
        assertFalse(predicate.test("spring.security.filterchains", Observation.Context()))

        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest("GET", "/api/v1/users/me/following-feed")))
        assertTrue(predicate.test("spring.security.filterchains", Observation.Context()))
    }

    @Test
    fun `요청이 아닌 곳(스케줄·Kafka 리스너)은 관측함`() {
        assertTrue(predicate.test("spring.kafka.listener", Observation.Context()))
        assertTrue(predicate.test("tasks.scheduled.execution", Observation.Context()))
    }
}
