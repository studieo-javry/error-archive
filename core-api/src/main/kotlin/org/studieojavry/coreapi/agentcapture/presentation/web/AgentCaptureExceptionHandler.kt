package org.studieojavry.coreapi.agentcapture.presentation.web

import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.studieojavry.coreapi.agentcapture.application.usecase.ConcurrentRequestException
import org.studieojavry.coreapi.agentcapture.domain.model.ArtifactHashMismatchException
import org.studieojavry.coreapi.agentcapture.domain.model.ArtifactTooLargeException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAlreadyImportedException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotReviewableException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSealedException
import org.studieojavry.coreapi.agentcapture.domain.model.EvidenceNotReadyException
import org.studieojavry.coreapi.agentcapture.domain.model.IdempotencyConflictException
import org.studieojavry.coreapi.agentcapture.domain.model.RevisionConflictException
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionWithoutAttemptException
import org.studieojavry.coreapi.agentcapture.domain.model.UnexecutedVerificationPassedException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownArtifactReferenceException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownAttemptReferenceException
import java.sql.SQLException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownSolutionReferenceException
import org.studieojavry.coreapi.agentcapture.domain.model.VerificationEvidenceRequiredException
import org.studieojavry.coreapi.agentcapture.domain.model.UnsupportedArtifactException

/**
 * agent 초안 경로 전용 예외 매핑. @Order(-1) 으로 공통 핸들러(GlobalExceptionHandler @Order MAX)
 * 와 **전역 GithubExceptionHandler(@Order 0)** 보다 먼저 매칭한다 — 특히 낙관락/무결성 위반은
 * github advice 가 전역으로 선점(CONNECTION_VERSION_CONFLICT)하므로 agent 경로에서 이기려면 더 높은
 * 우선순위가 필요하다. 이 advice 는 basePackages 로 agent 경로에만 적용되어 타 경로엔 무영향.
 * 코드 상수(CAPTURE_SEALED 등)를 problem detail 에 실어 커넥터가 분기할 수 있게 한다.
 */
@RestControllerAdvice(basePackages = ["org.studieojavry.coreapi.agentcapture"])
@Order(-1)
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

    /**
     * JPA 낙관락 실패 = 다른 요청이 같은 capture 를 동시에 수정하고 먼저 커밋. 데이터 정합은
     * 유지되나(한쪽만 적용) 패자에게 깔끔한 409 를 돌려줘야 한다. 커넥터는 revision 재조회 후 재시도.
     * (미매핑 시 전역 GithubExceptionHandler 가 엉뚱한 CONNECTION_VERSION_CONFLICT 로, 혹은 500 으로 샜다.)
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun optimisticLock(ex: ObjectOptimisticLockingFailureException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "REVISION_CONFLICT", "capture was modified concurrently; re-read revision and retry")

    /**
     * DB 무결성 위반. 유니크 위반(동시 중복 생성/쓰기 — capture_uuid·client_id·멱등영수증)은 사실상
     * 동시/중복 요청이므로 409 CONCURRENT_REQUEST(멱등 키로 재전송하면 최초 결과 재생). 그 외 무결성
     * 위반(FK·NOT NULL 등)은 입력/서버 문제이므로 공통 핸들러와 동일하게 500 으로 둬 버그를 숨기지 않는다.
     * (어드바이스에서 잡는 이유: saveAndFlush 아닌 save 는 commit 시점에 위반이 터져 service 의
     *  try/catch 를 피하므로, 컨트롤러 경계에서 포착해야 블록/커밋 어디서 터지든 일관 매핑된다.)
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun dataIntegrity(ex: DataIntegrityViolationException): ProblemDetail =
        if (isUniqueViolation(ex)) {
            problem(HttpStatus.CONFLICT, "CONCURRENT_REQUEST", "concurrent or duplicate request")
        } else {
            problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.mostSpecificCause.message)
        }

    @ExceptionHandler(UnknownAttemptReferenceException::class)
    fun unknownAttempt(ex: UnknownAttemptReferenceException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ATTEMPT_REFERENCE", ex.message)

    @ExceptionHandler(UnknownSolutionReferenceException::class)
    fun unknownSolution(ex: UnknownSolutionReferenceException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SOLUTION_REFERENCE", ex.message)

    @ExceptionHandler(UnknownArtifactReferenceException::class)
    fun unknownArtifact(ex: UnknownArtifactReferenceException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ARTIFACT_REFERENCE", ex.message)

    @ExceptionHandler(SolutionWithoutAttemptException::class)
    fun solutionWithoutAttempt(ex: SolutionWithoutAttemptException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "SOLUTION_WITHOUT_ATTEMPT", ex.message)

    @ExceptionHandler(UnexecutedVerificationPassedException::class)
    fun unexecutedVerificationPassed(ex: UnexecutedVerificationPassedException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNEXECUTED_VERIFICATION_PASSED", ex.message)

    @ExceptionHandler(VerificationEvidenceRequiredException::class)
    fun verificationEvidence(ex: VerificationEvidenceRequiredException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_EVIDENCE_REQUIRED", ex.message)

    @ExceptionHandler(EvidenceNotReadyException::class)
    fun evidenceNotReady(ex: EvidenceNotReadyException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_NOT_READY", ex.message)

    @ExceptionHandler(ArtifactHashMismatchException::class)
    fun hashMismatch(ex: ArtifactHashMismatchException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH", ex.message)

    @ExceptionHandler(ArtifactTooLargeException::class)
    fun tooLarge(ex: ArtifactTooLargeException): ProblemDetail =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", ex.message)

    @ExceptionHandler(UnsupportedArtifactException::class)
    fun unsupported(ex: UnsupportedArtifactException): ProblemDetail =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_ARTIFACT", ex.message)

    @ExceptionHandler(CaptureNotReviewableException::class)
    fun notReviewable(ex: CaptureNotReviewableException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "CAPTURE_NOT_REVIEWABLE", ex.message)

    @ExceptionHandler(CaptureAlreadyImportedException::class)
    fun alreadyImported(ex: CaptureAlreadyImportedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "CAPTURE_ALREADY_IMPORTED", ex.message)
            .apply { ex.caseId?.let { setProperty("caseId", it) } }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(ex: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            setProperty("code", code)
        }

    /** 원인 사슬에 Postgres unique_violation(SQLState 23505)이 있으면 유니크 제약 경합으로 본다. */
    private fun isUniqueViolation(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == UNIQUE_VIOLATION_SQLSTATE) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
