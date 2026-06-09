package org.studieojavry.sharederror.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.AuthenticationEntryPoint
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.ObjectMapper

private val log = KotlinLogging.logger {}

/**
 * Spring Security filter 단계의 인증 실패 → 401 + Enriched ProblemDetail.
 * - JWT 의 세부 사유를 5 가지 code 로 분류 (TOKEN_EXPIRED 등)
 * - TOKEN_EXPIRED 만 retryable=true (FE 가 refresh 자동 호출)
 * - WARN 로그 + X-Trace-Id 헤더
 * - AuthFailureMonitor 가 20 회 초과 시 brute-force 경고
 */
class ProblemDetailAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
    private val traceIdAccessor: TraceIdAccessor,
    private val failureMonitor: AuthFailureMonitor,
) : AuthenticationEntryPoint {

    override fun commence(req: HttpServletRequest, res: HttpServletResponse, ex: AuthenticationException) {
        val traceId = traceIdAccessor.currentOrNew()
        val code = classify(ex)
        val ip = req.remoteAddr ?: "-"

        val failures = failureMonitor.recordFailure(ip)
        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=$code] [ip=$ip] auth failed: ${ex.message}"
        }
        if (failures > 20) {
            log.warn { "[security] potential brute-force from ip=$ip ($failures failures in 5min)" }
        }

        res.status = HttpStatus.UNAUTHORIZED.value()
        res.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        res.setHeader("X-Trace-Id", traceId)

        val pd = ProblemDetailBuilder.build(
            status = HttpStatus.UNAUTHORIZED,
            detail = detailFor(code),
            code = code,
            traceId = traceId,
            retryable = code == SecurityErrorCodes.TOKEN_EXPIRED,
            instance = req.requestURI,
        )
        objectMapper.writeValue(res.outputStream, pd)
    }

    private fun classify(ex: AuthenticationException): String {
        if (ex is InvalidBearerTokenException) {
            val combined = (ex.message ?: "") + " " + (ex.cause?.message ?: "")
            return when {
                combined.contains("expired", ignoreCase = true)   -> SecurityErrorCodes.TOKEN_EXPIRED
                combined.contains("signature", ignoreCase = true) -> SecurityErrorCodes.INVALID_TOKEN_SIGNATURE
                combined.contains("aud", ignoreCase = true)       -> SecurityErrorCodes.INVALID_TOKEN_AUDIENCE
                else                                              -> SecurityErrorCodes.INVALID_TOKEN
            }
        }
        return SecurityErrorCodes.MISSING_TOKEN
    }

    private fun detailFor(code: String): String = when (code) {
        SecurityErrorCodes.TOKEN_EXPIRED           -> "Access token expired. Please refresh."
        SecurityErrorCodes.INVALID_TOKEN_SIGNATURE -> "Invalid token signature."
        SecurityErrorCodes.INVALID_TOKEN_AUDIENCE  -> "Token audience mismatch."
        SecurityErrorCodes.INVALID_TOKEN           -> "Invalid bearer token."
        SecurityErrorCodes.MISSING_TOKEN           -> "Authentication required."
        else                                       -> "Unauthenticated."
    }
}
