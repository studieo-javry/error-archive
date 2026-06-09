package org.studieojavry.iamapi.auth.presentation.web

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.iamapi.auth.application.usecase.RefreshAccessTokenUseCase
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor

private val log = KotlinLogging.logger {}

/**
 * auth 도메인 전용 advice. @Order(0) 으로 GlobalExceptionHandler 보다 우선 매칭.
 *
 * 일반 예외(NoSuchElement / IllegalArgument 등)는 shared-error 의 GlobalExceptionHandler 가
 * 처리하므로 여기엔 *도메인 고유 예외만* 둔다.
 */
@RestControllerAdvice
@Order(0)
class AuthExceptionHandler(
    private val traceIdAccessor: TraceIdAccessor,
) {

    @ExceptionHandler(RefreshAccessTokenUseCase.InvalidRefreshTokenException::class)
    fun handleInvalidRefresh(
        ex: RefreshAccessTokenUseCase.InvalidRefreshTokenException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=INVALID_REFRESH_TOKEN] ${ex.message}"
        }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.UNAUTHORIZED,
            detail = "Refresh token is invalid or expired. Please sign in again.",
            code = "INVALID_REFRESH_TOKEN",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }
}
