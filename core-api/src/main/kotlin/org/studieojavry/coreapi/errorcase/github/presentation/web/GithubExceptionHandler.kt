package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.usecase.CaseGithubLinkNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubConnectionCandidateInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubConnectionNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubConnectionVersionConflictException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEventCursorInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubImportInvalidRequestException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubImportVisibilityException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallStateInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallationAlreadyBoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubInstallationNotOwnedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubItemNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRateLimitedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubReauthorizationRequiredException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRepositoryNotConnectedException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubRepositorySelectionInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubScopeMismatchException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubStepLinkInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubUrlInvalidException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubWebhookSignatureException
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.sharederror.problem.ProblemDetailBuilder
import org.studieojavry.sharederror.trace.TraceIdAccessor

private val log = KotlinLogging.logger {}

/**
 * GitHub BC 의 도메인 advice. @Order(0) 으로 GlobalExceptionHandler(@Order MAX) 보다 우선.
 *
 * 컨트롤러(연결/설치/케이스-근거/이슈-임포트/웹훅)의 수동 try/catch 를 없애고, 도메인 예외를 여기서
 * [GithubErrorCodes] 의 안정적인 `code` + RFC9457 ProblemDetail 로 일관 변환한다.
 *
 * GithubRateLimitedException 은 과거 errorcase advice(ErrorCaseExceptionHandler)가 처리했으나,
 * 같은 @Order(0) advice 두 곳이 같은 예외를 잡으면 순서가 불명확해지므로 이 advice 로 일원화했다
 * (두 advice 가 겹치는 예외를 잡지 않도록). 429 는 상태코드가 4xx 라 GlobalExceptionHandler 의
 * BaseAppException 경로로는 Retry-After 가 안 붙으므로, 여기서 직접 헤더를 세팅한다.
 */
@RestControllerAdvice
@Order(0)
@ConditionalOnGithubEnabled
class GithubExceptionHandler(
    private val traceIdAccessor: TraceIdAccessor,
) {

    // --- 400 Bad Request ---

    @ExceptionHandler(GithubInstallStateInvalidException::class)
    fun handleStateInvalid(ex: GithubInstallStateInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.INSTALL_STATE_INVALID, ex.message ?: "Invalid install state.")

    @ExceptionHandler(GithubConnectionCandidateInvalidException::class)
    fun handleCandidateInvalid(ex: GithubConnectionCandidateInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.CONNECTION_CANDIDATE_INVALID, ex.message ?: "Invalid candidate token.")

    @ExceptionHandler(GithubRepositorySelectionInvalidException::class)
    fun handleRepoSelectionInvalid(ex: GithubRepositorySelectionInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.REPOSITORY_SELECTION_INVALID, ex.message ?: "Invalid repository selection.")

    @ExceptionHandler(GithubUrlInvalidException::class)
    fun handleUrlInvalid(ex: GithubUrlInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.URL_INVALID, ex.message ?: "Unsupported GitHub URL.")

    @ExceptionHandler(GithubStepLinkInvalidException::class)
    fun handleStepLinkInvalid(ex: GithubStepLinkInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.STEP_LINK_INVALID, ex.message ?: "Invalid step link.")

    @ExceptionHandler(GithubEventCursorInvalidException::class)
    fun handleCursorInvalid(ex: GithubEventCursorInvalidException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.EVENT_CURSOR_INVALID, ex.message ?: "Invalid timeline cursor.")

    @ExceptionHandler(GithubImportVisibilityException::class)
    fun handleImportVisibility(ex: GithubImportVisibilityException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.IMPORT_VISIBILITY_INVALID, ex.message ?: "Invalid visibility for import.")

    @ExceptionHandler(GithubImportInvalidRequestException::class)
    fun handleImportInvalid(ex: GithubImportInvalidRequestException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.IMPORT_INVALID_REQUEST, ex.message ?: "Invalid import request.")

    // --- 401 Unauthorized ---

    @ExceptionHandler(GithubWebhookSignatureException::class)
    fun handleWebhookSignature(ex: GithubWebhookSignatureException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.UNAUTHORIZED, GithubErrorCodes.WEBHOOK_SIGNATURE_INVALID, ex.message ?: "Invalid webhook signature.")

    // --- 403 Forbidden ---

    @ExceptionHandler(GithubInstallationNotOwnedException::class)
    fun handleNotOwned(ex: GithubInstallationNotOwnedException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.FORBIDDEN, GithubErrorCodes.INSTALLATION_NOT_OWNED, ex.message ?: "Installation not accessible by this account.")

    @ExceptionHandler(GithubRepositoryNotConnectedException::class)
    fun handleRepoNotConnected(ex: GithubRepositoryNotConnectedException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.FORBIDDEN, GithubErrorCodes.REPOSITORY_NOT_CONNECTED, ex.message ?: "Repository is not connected.")

    @ExceptionHandler(GithubScopeMismatchException::class)
    fun handleScopeMismatch(ex: GithubScopeMismatchException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.FORBIDDEN, GithubErrorCodes.SCOPE_MISMATCH, ex.message ?: "Repository scope does not match the case.")

    @ExceptionHandler(GithubReauthorizationRequiredException::class)
    fun handleReauthRequired(ex: GithubReauthorizationRequiredException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.FORBIDDEN, GithubErrorCodes.REAUTHORIZATION_REQUIRED, ex.message ?: "GitHub re-authorization required.")

    // --- 404 Not Found ---

    @ExceptionHandler(GithubConnectionNotFoundException::class)
    fun handleConnectionNotFound(ex: GithubConnectionNotFoundException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.NOT_FOUND, GithubErrorCodes.CONNECTION_NOT_FOUND, ex.message ?: "Connection not found.")

    @ExceptionHandler(GithubItemNotFoundException::class)
    fun handleItemNotFound(ex: GithubItemNotFoundException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.NOT_FOUND, GithubErrorCodes.ITEM_NOT_FOUND, ex.message ?: "Issue/PR not found.")

    @ExceptionHandler(CaseGithubLinkNotFoundException::class)
    fun handleLinkNotFound(ex: CaseGithubLinkNotFoundException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.NOT_FOUND, GithubErrorCodes.CASE_LINK_NOT_FOUND, ex.message ?: "Case GitHub link not found.")

    // --- 409 Conflict ---

    @ExceptionHandler(GithubInstallationAlreadyBoundException::class)
    fun handleAlreadyBound(ex: GithubInstallationAlreadyBoundException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.CONFLICT, GithubErrorCodes.INSTALLATION_ALREADY_BOUND, ex.message ?: "Installation already connected to this space.")

    @ExceptionHandler(GithubConnectionVersionConflictException::class)
    fun handleVersionConflict(ex: GithubConnectionVersionConflictException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.CONFLICT, GithubErrorCodes.CONNECTION_VERSION_CONFLICT, ex.message ?: "다른 관리자가 먼저 변경했습니다 — 새로고침 후 다시 시도하세요.")

    /** JPA @Version 이 커밋 시점에 터뜨리는 실제 동시 커밋 충돌 — 버전 불일치와 같은 409·같은 code 로 돌려준다. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: ObjectOptimisticLockingFailureException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail =
        build(req, res, HttpStatus.CONFLICT, GithubErrorCodes.CONNECTION_VERSION_CONFLICT, "다른 관리자가 먼저 변경했습니다 — 새로고침 후 다시 시도하세요.")

    // --- 429 / 400 / 503: GitHub upstream ---

    /**
     * GitHub 호출 한도 초과 — 장애(503)와 구분해 **429 + Retry-After(초, 올림)**. FE 는 "잠시 후 다시" 안내에 쓴다.
     */
    @ExceptionHandler(GithubRateLimitedException::class)
    fun handleRateLimited(ex: GithubRateLimitedException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        val seconds = maxOf(1L, ex.retryAfter.plusMillis(999).seconds)
        res.setHeader("Retry-After", seconds.toString())
        return build(req, res, HttpStatus.TOO_MANY_REQUESTS, GithubErrorCodes.RATE_LIMITED, "GitHub 호출 한도에 걸렸습니다. ${seconds}초 뒤 다시 시도해 주세요.", retryable = true)
    }

    /**
     * 어댑터에서 올라온 GitHub API 오류. retryAfter 가 있으면 한도초과(429)로, clientError 면 400, 그 외는
     * 일시 장애(503, retryable)로 매핑한다. (예전에는 컨트롤러가 이 로직을 중복 보유했다.)
     */
    @ExceptionHandler(GithubApiException::class)
    fun handleApi(ex: GithubApiException, req: HttpServletRequest, res: HttpServletResponse): ProblemDetail {
        ex.retryAfter?.let { return handleRateLimited(GithubRateLimitedException(it, ex), req, res) }
        return if (ex.clientError) {
            build(req, res, HttpStatus.BAD_REQUEST, GithubErrorCodes.UPSTREAM_INVALID, ex.message ?: "GitHub rejected the request.")
        } else {
            build(req, res, HttpStatus.SERVICE_UNAVAILABLE, GithubErrorCodes.UPSTREAM_UNAVAILABLE, ex.message ?: "GitHub API is temporarily unavailable.", retryable = true)
        }
    }

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
