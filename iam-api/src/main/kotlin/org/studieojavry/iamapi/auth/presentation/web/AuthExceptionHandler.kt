package org.studieojavry.iamapi.auth.presentation.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.iamapi.auth.application.usecase.RefreshAccessTokenUseCase

@RestControllerAdvice
class AuthExceptionHandler {

    @ExceptionHandler(RefreshAccessTokenUseCase.InvalidRefreshTokenException::class)
    fun handleInvalidRefresh(ex: RefreshAccessTokenUseCase.InvalidRefreshTokenException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid_refresh_token").also {
            it.title = "Invalid refresh token"
        }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "not_found")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "bad_request")

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "authentication_failed")
}