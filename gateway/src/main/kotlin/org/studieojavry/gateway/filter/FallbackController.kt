package org.studieojavry.gateway.filter

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * CircuitBreaker 가 OPEN 또는 timeout 일 때 라우트되는 fallback.
 * yml 에서 `fallbackPath: /__fallback/{service}` 로 지정.
 *
 * `Retry-After` 헤더로 클라이언트의 자동 재시도 정책을 표준화한다 (RFC 9110 §10.2.3).
 * 값은 ResilienceConfig 의 waitDurationInOpenState(30s) 와 일치시킴 — 재시도 시점에
 * CircuitBreaker 가 HALF_OPEN 으로 전이될 가능성이 가장 높음.
 */
@RestController
@RequestMapping("/__fallback")
class FallbackController {

    @RequestMapping(
        path = ["/{service}"],
        method = [
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE
        ]
    )
    fun fallback(@PathVariable service: String): ResponseEntity<Map<String, Any>> {
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
            .body(
                mapOf(
                    "error" to "service_unavailable",
                    "service" to service,
                    "message" to "downstream service is temporarily unavailable, please retry later",
                    "retryAfterSeconds" to RETRY_AFTER_SECONDS,
                    "timestamp" to Instant.now().toString()
                )
            )
    }

    companion object {
        private const val RETRY_AFTER_SECONDS = 30L
    }
}
