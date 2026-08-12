package org.studieojavry.sharederror.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.ObjectMapper

private val log = KotlinLogging.logger {}

/**
 * Spring Security filter 단계의 인가 실패 → 403 + Enriched ProblemDetail.
 * 단일 code: ACCESS_DENIED (§15 #9).
 */
class ProblemDetailAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
    private val traceIdAccessor: TraceIdAccessor,
) : AccessDeniedHandler {

    override fun handle(req: HttpServletRequest, res: HttpServletResponse, ex: AccessDeniedException) {
        val traceId = traceIdAccessor.currentOrNew()
        val userId = SecurityContextHolder.getContext().authentication?.name

        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [userId=$userId] [code=${SecurityErrorCodes.ACCESS_DENIED}] access denied: ${ex.message}"
        }

        res.status = HttpStatus.FORBIDDEN.value()
        res.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        res.setHeader("X-Trace-Id", traceId)

        val pd = ProblemDetailBuilder.build(
            status = HttpStatus.FORBIDDEN,
            detail = "Access denied.",
            code = SecurityErrorCodes.ACCESS_DENIED,
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
        objectMapper.writeValue(res.outputStream, pd)
    }
}
