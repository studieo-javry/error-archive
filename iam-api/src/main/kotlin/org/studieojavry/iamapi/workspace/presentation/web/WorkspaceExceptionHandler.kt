package org.studieojavry.iamapi.workspace.presentation.web

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.iamapi.workspace.application.usecase.AcceptInvitationUseCase
import org.studieojavry.iamapi.workspace.application.usecase.CreateWorkspaceUseCase
import org.studieojavry.iamapi.workspace.application.usecase.WorkspaceAccess
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor

private val log = KotlinLogging.logger {}

/**
 * workspace 도메인 전용 advice. @Order(0) 으로 GlobalExceptionHandler 보다 우선.
 */
@RestControllerAdvice
@Order(0)
class WorkspaceExceptionHandler(
    private val traceIdAccessor: TraceIdAccessor,
) {

    @ExceptionHandler(WorkspaceAccess.AccessDeniedException::class)
    fun handleAccessDenied(
        ex: WorkspaceAccess.AccessDeniedException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=WORKSPACE_ACCESS_DENIED] ${ex.message}"
        }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.FORBIDDEN,
            detail = ex.message ?: "Workspace access denied.",
            code = "WORKSPACE_ACCESS_DENIED",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    @ExceptionHandler(CreateWorkspaceUseCase.DuplicateSlugException::class)
    fun handleDuplicateSlug(
        ex: CreateWorkspaceUseCase.DuplicateSlugException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=WORKSPACE_SLUG_TAKEN] ${ex.message}"
        }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.CONFLICT,
            detail = "Workspace slug '${ex.slug}' is already in use.",
            code = "WORKSPACE_SLUG_TAKEN",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }

    @ExceptionHandler(AcceptInvitationUseCase.InvalidInvitationException::class)
    fun handleInvalidInvitation(
        ex: AcceptInvitationUseCase.InvalidInvitationException,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ProblemDetail {
        val traceId = traceIdAccessor.currentOrNew()
        log.warn {
            "[traceId=$traceId] [endpoint=${req.method} ${req.requestURI}] [code=INVALID_INVITATION] ${ex.message}"
        }
        res.setHeader("X-Trace-Id", traceId)
        return ProblemDetailBuilder.build(
            status = HttpStatus.GONE,
            detail = ex.message ?: "Invitation is invalid or expired.",
            code = "INVALID_INVITATION",
            traceId = traceId,
            retryable = false,
            instance = req.requestURI,
        )
    }
}
