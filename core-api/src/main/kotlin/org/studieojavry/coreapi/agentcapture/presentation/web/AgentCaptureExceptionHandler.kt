package org.studieojavry.coreapi.agentcapture.presentation.web

import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.coreapi.agentcapture.application.usecase.ConcurrentRequestException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSealedException
import org.studieojavry.coreapi.agentcapture.domain.model.IdempotencyConflictException
import org.studieojavry.coreapi.agentcapture.domain.model.RevisionConflictException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownAttemptReferenceException

/**
 * agent 초안 경로 전용 예외 매핑. @Order(0) 으로 공통 핸들러보다 먼저 매칭.
 * 코드 상수(CAPTURE_SEALED 등)를 problem detail 에 실어 커넥터가 분기할 수 있게 한다.
 */
@RestControllerAdvice(basePackages = ["org.studieojavry.coreapi.agentcapture"])
@Order(0)
class AgentCaptureExceptionHandler {

    @ExceptionHandler(CaptureNotFoundException::class)
    fun notFound(ex: CaptureNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "CAPTURE_NOT_FOUND", ex.message)

    @ExceptionHandler(CaptureSealedException::class)
    fun sealed(ex: CaptureSealedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "CAPTURE_SEALED", ex.message)

    @ExceptionHandler(RevisionConflictException::class)
    fun revision(ex: RevisionConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "REVISION_CONFLICT", ex.message)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun idempotency(ex: IdempotencyConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.message)

    @ExceptionHandler(ConcurrentRequestException::class)
    fun concurrent(ex: ConcurrentRequestException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "CONCURRENT_REQUEST", ex.message)

    @ExceptionHandler(UnknownAttemptReferenceException::class)
    fun unknownAttempt(ex: UnknownAttemptReferenceException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ATTEMPT_REFERENCE", ex.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(ex: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            setProperty("code", code)
        }
}
