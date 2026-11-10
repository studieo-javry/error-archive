package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureArtifact
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContentSanitizer
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.EvidenceNotReadyException
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownAttemptReferenceException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 세션 등록(동일 sessionUuid 재사용). */
@Service
class RegisterCaptureSessionUseCase(
    private val sessions: CaptureSessionRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: RegisterSessionCommand): CaptureSession =
        sessions.findByUuid(cmd.sessionUuid) ?: sessions.save(
            CaptureSession.register(
                sessionUuid = cmd.sessionUuid,
                connectionId = cmd.connectionId,
                clientKind = cmd.clientKind,
                clientVersion = cmd.clientVersion,
                clientSessionId = cmd.clientSessionId,
                now = Instant.now(clock),
            )
        )
}

data class RegisterSessionCommand(
    val sessionUuid: UUID,
    val connectionId: UUID,
    val clientKind: String,
    val clientVersion: String?,
    val clientSessionId: String,
)

/** OPEN 초안 생성. capture_uuid UNIQUE 로 재전송에서도 중복 생성되지 않는다. */
@Service
class CreateCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val sessions: CaptureSessionRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: CreateCaptureCommand): AgentCapture {
        val saved = captures.save(
            AgentCapture.start(
                captureUuid = cmd.captureUuid,
                ownerUserId = cmd.ownerUserId,
                originatingConnectionId = cmd.connectionId,
                title = cmd.title,
                project = cmd.project,
                content = CaptureContentSanitizer.sanitize(cmd.content),
                now = Instant.now(clock),
            )
        )
        cmd.sessionUuid?.let { sessionUuid ->
            sessions.findByUuid(sessionUuid)?.let { session -> sessions.link(session.id!!, saved.id!!) }
        }
        return saved
    }
}

data class CreateCaptureCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val connectionId: UUID,
    val title: String?,
    val project: String?,
    val content: CaptureContent,
    val sessionUuid: UUID?,
)

/** 초안 조회·재개. attempts·solutions 포함. 본인 소유만(아니면 404). */
@Service
class GetCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val solutions: CaptureSolutionRepositoryPort,
    private val artifacts: CaptureArtifactRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(captureUuid: UUID, ownerUserId: Long): CaptureView {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        return CaptureView(
            capture = capture,
            attempts = attempts.listByCapture(capture.id!!),
            solutions = solutions.listByCapture(capture.id!!),
            artifacts = artifacts.listByCapture(capture.id!!),
        )
    }
}

data class CaptureView(
    val capture: AgentCapture,
    val attempts: List<CaptureAttempt>,
    val solutions: List<CaptureSolutionCandidate>,
    val artifacts: List<CaptureArtifact>,
)

/** OPEN 초안 기본 정보 교체(title/project/content 전체 교체). expectedRevision 검사. */
@Service
class PatchCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: PatchCaptureCommand): AgentCapture {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.ownerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.requireRevision(cmd.expectedRevision)
        capture.edit(cmd.title, cmd.project, CaptureContentSanitizer.sanitize(cmd.content), Instant.now(clock))
        return captures.save(capture)
    }
}

data class PatchCaptureCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val expectedRevision: Int?,
    val title: String?,
    val project: String?,
    val content: CaptureContent,
)

/** Attempt 추가(append). clientAttemptId UNIQUE 로 중복 방지. OPEN 에서만. */
@Service
class AddAttemptUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: AddAttemptCommand): CaptureAttempt {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.ownerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.requireAgentWritable()
        val order = attempts.countByCapture(capture.id!!)
        return attempts.save(
            CaptureAttempt.create(
                captureId = capture.id!!,
                clientAttemptId = cmd.clientAttemptId,
                orderIndex = order,
                result = cmd.result,
                attemptType = cmd.attemptType,
                detail = CaptureContentSanitizer.sanitize(cmd.detail),
                now = Instant.now(clock),
            )
        )
    }
}

data class AddAttemptCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val clientAttemptId: String,
    val result: AttemptResult,
    val attemptType: String?,
    val detail: AttemptDetail,
)

/** 기존 Attempt 갱신. OPEN 에서만. 모르는 clientAttemptId → 404. */
@Service
class UpdateAttemptUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: UpdateAttemptCommand): CaptureAttempt {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.ownerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.requireAgentWritable()
        val attempt = attempts.findByCaptureAndClientId(capture.id!!, cmd.clientAttemptId)
            ?: throw UnknownAttemptReferenceException(cmd.captureUuid, cmd.clientAttemptId)
        attempt.update(cmd.result, cmd.attemptType, CaptureContentSanitizer.sanitize(cmd.detail), Instant.now(clock))
        return attempts.save(attempt)
    }
}

data class UpdateAttemptCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val clientAttemptId: String,
    val result: AttemptResult,
    val attemptType: String?,
    val detail: AttemptDetail,
)

/** Solution 후보 추가. 참조한 clientAttemptId 는 같은 Capture 의 Attempt 여야 한다. OPEN 에서만. */
@Service
class AddSolutionUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val solutions: CaptureSolutionRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: AddSolutionCommand): CaptureSolutionCandidate {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.ownerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.requireAgentWritable()
        val known = attempts.clientAttemptIds(capture.id!!)
        cmd.content.clientAttemptIds.forEach { ref ->
            if (ref !in known) throw UnknownAttemptReferenceException(cmd.captureUuid, ref)
        }
        return solutions.save(
            CaptureSolutionCandidate.create(
                captureId = capture.id!!,
                clientSolutionId = cmd.clientSolutionId,
                title = cmd.title,
                content = CaptureContentSanitizer.sanitize(cmd.content),
                now = Instant.now(clock),
            )
        )
    }
}

data class AddSolutionCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val clientSolutionId: String,
    val title: String,
    val content: SolutionContent,
)

/**
 * OPEN → READY_FOR_REVIEW 봉인. 문제 해결 여부와 무관. expectedRevision 검사.
 *
 * 봉인 전 참조한 근거(Attempt 의 evidenceRefs)가 모두 업로드 완료됐는지 확인한다. 아직 준비되지 않은
 * 근거가 있으면 422 EVIDENCE_NOT_READY. (제외는 웹 검토 경로에서 — PR4.)
 */
@Service
class CompleteCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val artifacts: CaptureArtifactRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(captureUuid: UUID, ownerUserId: Long, expectedRevision: Int?): AgentCapture {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        capture.requireRevision(expectedRevision)

        val referenced = attempts.listByCapture(capture.id!!).flatMap { it.detail.evidenceRefs }.toSet()
        if (referenced.isNotEmpty()) {
            val ready = artifacts.completedClientIds(capture.id!!)
            val missing = referenced.filter { it !in ready }
            if (missing.isNotEmpty()) throw EvidenceNotReadyException(captureUuid, missing.sorted())
        }

        capture.complete(Instant.now(clock))
        return captures.save(capture)
    }
}
