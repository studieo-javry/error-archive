package org.studieojavry.coreapi.shared.presentation

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseDeleteForbiddenException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseLinkException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.shared.infrastructure.iam.IamApiUnavailableException
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor

private val log = KotlinLogging.logger {}

/**
 * errorcase BC 의 도메인 advice. @Order(0) 으로 GlobalExceptionHandler 보다 우선.
 *
 * Controller 의 수동 try/catch 가 사라지면 모든 도메인 예외가 여기로 모인다.
 * 단계적 도입 — 우선 가장 자주 발생하는 5 종 등록.
 */
@RestControllerAdvice
@Order(0)
class ErrorCaseExceptionHandler(
    private val traceIdAccessor: TraceIdAccessor,
) {

    @ExceptionHandler(WorkspaceAccessDeniedException::class)
    fun handleWorkspaceAccessDenied(
        ex: WorkspaceAccessDeniedException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED", ex.message ?: "Workspace access denied.")

    @ExceptionHandler(ErrorCaseAccessDeniedException::class)
    fun handleCaseAccessDenied(
        ex: ErrorCaseAccessDeniedException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.FORBIDDEN, "CASE_ACCESS_DENIED", ex.message ?: "Case access denied.")

    @ExceptionHandler(ErrorCaseDeleteForbiddenException::class)
    fun handleCaseDeleteForbidden(
        ex: ErrorCaseDeleteForbiddenException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.FORBIDDEN, "CASE_DELETE_FORBIDDEN", ex.message ?: "Case cannot be deleted.")

    @ExceptionHandler(ErrorCaseNotFoundException::class)
    fun handleCaseNotFound(
        ex: ErrorCaseNotFoundException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", ex.message ?: "Case not found.")

    @ExceptionHandler(ErrorCaseLinkException::class)
    fun handleLinkException(
        ex: ErrorCaseLinkException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.BAD_REQUEST, "CASE_LINK_INVALID", ex.message ?: "Invalid snippet/attachment link.")

    /**
     * iam-api 지연/중단으로 권한(워크스페이스 role) 확인이 불가한 경우 — **일시 장애**.
     * 500(서버 오류)이 아니라 **503(Service Unavailable, retryable)** 로 내려 클라이언트가 재시도 가능함을 인지시킨다.
     * 접근은 여전히 거부(fail-closed) — 이 예외가 던져진 시점에 권한이 확인되지 않았으므로 데이터는 반환되지 않는다.
     */
    @ExceptionHandler(IamApiUnavailableException::class)
    fun handleIamUnavailable(
        ex: IamApiUnavailableException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail = build(req, res, HttpStatus.SERVICE_UNAVAILABLE, "IAM_UNAVAILABLE", "Authorization service temporarily unavailable. Please retry.", retryable = true)

    private fun build(
        req: HttpServletRequest,
        res: HttpServletResponse,
        status: HttpStatus,
        code: String,
        detail: String,
        retryable: Boolean = false,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=$code] $detail" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = status,
            detail = detail,
            code = code,
            traceId = traceId,
            retryable = retryable,
            instance = req.requestURI,
        )
    }
}
