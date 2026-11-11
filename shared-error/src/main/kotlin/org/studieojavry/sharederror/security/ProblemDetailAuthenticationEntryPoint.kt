package org.studieojavry.sharederror.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
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
        if (isBearerTokenAuthError(ex)) {
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

    /**
     * oauth2-resource-server 의 `InvalidBearerTokenException` 여부를 **클래스패스에 의존하지 않고** 판별한다.
     *
     * shared-error 는 이 스타터를 compileOnly 로만 의존한다(소비 서비스가 런타임에 가져온다는 가정).
     * 그러나 내부 JWT 만 쓰는 서비스(예: core-api)는 런타임에 그 라이브러리가 없어, `ex is
     * InvalidBearerTokenException` 같은 직접 타입 참조는 미인증 요청 처리 중 NoClassDefFoundError 를
     * 일으켜 401 대신 500 을 낸다. 실제 발생한 예외 인스턴스의 상위 클래스 **이름**만 훑으므로
     * (이미 로드된 클래스들), 부재(不在) 클래스를 강제로 로드하지 않는다.
     */
    private fun isBearerTokenAuthError(ex: Throwable): Boolean {
        var type: Class<*>? = ex.javaClass
        while (type != null) {
            if (type.name == BEARER_TOKEN_EXCEPTION) return true
            type = type.superclass
        }
        return false
    }

    private companion object {
        private const val BEARER_TOKEN_EXCEPTION =
            "org.springframework.security.oauth2.server.resource.InvalidBearerTokenException"
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
