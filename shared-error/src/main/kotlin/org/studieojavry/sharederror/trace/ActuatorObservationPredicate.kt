package org.studieojavry.sharederror.trace

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationPredicate
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 헬스체크(kubelet 프로브)·메트릭 수집(Prometheus) 요청은 관측하지 않는다 — 추적·`http.server.requests` 지표 둘 다.
 *
 * 15분에 수백 건이라 진짜 요청 추적이 묻히고 저장소만 차지한다.
 * 요청 관측(`http.server.requests`)만 끄면 그 아래 Security 필터 관측이 부모 없이 따로 추적을 만들므로,
 * 같은 요청 처리 중의 관측은 전부 끈다(요청 스레드에 걸린 [RequestContextHolder] 로 판단).
 * 요청이 아닌 곳(스케줄·Kafka 리스너)은 그대로 관측한다.
 */
class ActuatorObservationPredicate : ObservationPredicate {

    override fun test(name: String, context: Observation.Context): Boolean {
        val request = (context as? ServerRequestObservationContext)?.carrier
            ?: (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return request == null || !isActuator(request)
    }

    private fun isActuator(request: HttpServletRequest): Boolean =
        PATHS.any { request.requestURI.startsWith(it) }

    companion object {
        /** prod 는 base-path=/internal/actuator, local 은 기본 /actuator. */
        private val PATHS = listOf("/internal/actuator", "/actuator")
    }
}
