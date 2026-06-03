package org.studieojavry.iamapi.workspace.presentation.web

import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.iamapi.workspace.application.usecase.AcceptInvitationUseCase
import org.studieojavry.iamapi.workspace.application.usecase.WorkspaceAccess

@RestControllerAdvice
@Order(0)
class WorkspaceExceptionHandler {

    @ExceptionHandler(WorkspaceAccess.AccessDeniedException::class)
    fun handleAccessDenied(ex: WorkspaceAccess.AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.message ?: "forbidden")

    @ExceptionHandler(AcceptInvitationUseCase.InvalidInvitationException::class)
    fun handleInvalidInvitation(ex: AcceptInvitationUseCase.InvalidInvitationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.message ?: "invalid_invitation")
}