package org.studieojavry.sharederror.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.sharederror.exception.BaseAppException
import org.studieojavry.sharederror.masking.ErrorMaskingPolicy
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor

private val log = KotlinLogging.logger {}

/**
 * 모든 서비스의 글로벌 fallback advice.
 * @Order(MAX_VALUE) — 도메인 advice 가 못 잡은 예외만 처리.
 *
 * 각 서비스의 도메인 advice 는 더 낮은 @Order (예: 0) 로 선언해 우선 매칭.
 */
@RestControllerAdvice
@Order(Int.MAX_VALUE)
open class GlobalExceptionHandler(
    protected val traceIdAccessor: TraceIdAccessor,
    protected val maskingPolicy: ErrorMaskingPolicy,
) {

    @ExceptionHandler(BaseAppException::class)
    open fun handleApp(ex: BaseAppException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        val userId = SecurityContextHolder.getContext().authentication?.name
        val msg = "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [userId=$userId] [code=${ex.code}] ${ex.message}"
        if (ex.status.is5xxServerError) log.error(ex) { msg } else log.warn { msg }

        res.setHeader("X-Trace-Id", traceId)
        if (ex.retryable && ex.status.is5xxServerError) {
            val secs = (ex.retryAfterSeconds ?: 10L).toString()
            res.setHeader(HttpHeaders.RETRY_AFTER, secs)
        }
        return ProblemDetailBuilder.build(
            status = ex.status,
            detail = ex.message ?: ex.status.reasonPhrase,
            code = ex.code,
            traceId = traceId,
            retryable = ex.retryable,
            instance = req.requestURI,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    open fun handleValidation(ex: MethodArgumentNotValidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        val errors = ValidationErrorBuilder.from(ex.bindingResult)
        log.info { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=VALIDATION_FAILED] ${errors.size} field(s) failed" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.BAD_REQUEST,
            detail = "${errors.size} field(s) failed validation",
            code = "VALIDATION_FAILED",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
            extra = mapOf("errors" to errors),
        )
    }

    @ExceptionHandler(NoSuchElementException::class)
    open fun handleNotFound(ex: NoSuchElementException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=NOT_FOUND] ${ex.message}" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.NOT_FOUND,
            detail = ex.message ?: "Resource not found",
            code = "NOT_FOUND",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    open fun handleBadRequest(ex: IllegalArgumentException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=BAD_REQUEST] ${ex.message}" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.BAD_REQUEST,
            detail = ex.message ?: "Bad request",
            code = "BAD_REQUEST",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    /**
     * 잘못된 HTTP 메서드 — 405 (500 아님). 클라이언트 실수이지 서버 장애가 아니므로
     * gateway 서킷브레이커가 실패로 세지 않도록 4xx 로 매핑한다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    open fun handleMethodNotAllowed(ex: HttpRequestMethodNotSupportedException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=METHOD_NOT_ALLOWED] ${ex.message}" }
        res.setHeader("X-Trace-Id", traceId)
        ex.supportedHttpMethods?.let { res.setHeader(HttpHeaders.ALLOW, it.joinToString(", ")) }
        return ProblemDetailBuilder.build(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            detail = ex.message ?: "Method not allowed",
            code = "METHOD_NOT_ALLOWED",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    /**
     * 파싱 불가한 본문(깨진/누락 JSON) — 400 (500 아님). 위와 동일 이유로 4xx.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    open fun handleUnreadable(ex: HttpMessageNotReadableException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=MALFORMED_REQUEST] unreadable body" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.BAD_REQUEST,
            detail = "Malformed or missing request body",
            code = "MALFORMED_REQUEST",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    /**
     * `ResponseStatusException` — 예외가 스스로 담은 상태코드(예: 401 missing principal)를 그대로 보존.
     * 이전엔 Throwable fallback 으로 떨어져 401/403 이 500 으로 둔갑 → gateway 서킷 오염 → 503 유발했다.
     * 4xx 는 client 오류이므로 서킷 실패로 세면 안 된다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    open fun handleResponseStatus(ex: ResponseStatusException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        val status = HttpStatus.valueOf(ex.statusCode.value())
        val msg = "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=${status.name}] ${ex.reason}"
        if (status.is5xxServerError) log.error(ex) { msg } else log.warn { msg }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = status,
            detail = ex.reason ?: status.reasonPhrase,
            code = status.name,
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    /**
     * 인가 실패(권한 부족) — 403. Security 필터가 못 잡고 advice 까지 온 경우를 4xx 로 보존.
     */
    @ExceptionHandler(AccessDeniedException::class)
    open fun handleAccessDenied(ex: AccessDeniedException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        val userId = SecurityContextHolder.getContext().authentication?.name
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [userId=$userId] [code=FORBIDDEN] ${ex.message}" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.FORBIDDEN,
            detail = "Access denied",
            code = "FORBIDDEN",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    @ExceptionHandler(Throwable::class)
    open fun handleUnexpected(ex: Throwable, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        val userId = SecurityContextHolder.getContext().authentication?.name
        log.error(ex) {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [userId=$userId] [code=INTERNAL_ERROR] Unhandled exception"
        }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            detail = maskingPolicy.maskedDetailFor5xx(ex, traceId),
            code = "INTERNAL_ERROR",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }
}
