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

    private fun build(
        req: HttpServletRequest,
        res: HttpServletResponse,
        status: HttpStatus,
        code: String,
        detail: String,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn { "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=$code] $detail" }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = status,
            detail = detail,
            code = code,
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }
}
