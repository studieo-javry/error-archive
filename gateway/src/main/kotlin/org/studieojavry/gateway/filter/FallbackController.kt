package org.studieojavry.gateway.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.sharederror.trace.TraceIdAccessor
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * CircuitBreaker 가 OPEN 또는 timeout 일 때 라우트되는 fallback.
 * yml 에서 `fallbackPath: /__fallback/{service}` 로 지정.
 *
 * 응답 정책 (§6 error-handling-design.md):
 *  - body 는 사용자 친화 — ProblemDetail 형식에 *억지로 맞추지 않음*
 *  - `Retry-After` 헤더 (RFC 9110 §10.2.3) — ResilienceConfig 의 waitDurationInOpenState(30s) 일치
 *  - **`X-Trace-Id` 헤더로만 traceId 노출** — body 에는 안 둠 (사용자 화면 노이즈 회피)
 *  - 로그에 traceId / ip / service 남김 — 운영의 시간대별 차단 추적용
 *
 * fallback 은 *시간대 단위 차단* 이라 *개별 traceId 의 디버그 가치 낮음*.
 * 사용자 body 는 깔끔하게 / 운영은 헤더 + WARN 로그로 추적.
 */
@RestController
@RequestMapping("/__fallback")
class FallbackController(
    private val traceIdAccessor: TraceIdAccessor,
) {

    @RequestMapping(
        path = ["/{service}"],
        method = [
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE
        ]
    )
    fun fallback(
        @PathVariable service: String,
        req: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn {
            "[fallback] service=$service traceId=$traceId ip=${req.remoteAddr} — CircuitBreaker OPEN or timeout"
        }

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
            .header("X-Trace-Id", traceId)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                mapOf(
                    "title" to "Service temporarily unavailable",
                    "status" to HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "detail" to "잠시 후 다시 시도해 주세요. ${RETRY_AFTER_SECONDS} 초 후 자동 복구를 시도합니다.",
                    "service" to service,
                    "code" to "SERVICE_UNAVAILABLE",
                    "timestamp" to Instant.now().toString(),
                    "retryable" to true,
                    "retryAfterSeconds" to RETRY_AFTER_SECONDS
                )
            )
    }

    companion object {
        private const val RETRY_AFTER_SECONDS = 30L
    }
}
