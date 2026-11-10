package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureImportRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.command.CreateAttachmentCommand
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.CreateAttachmentUseCase
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAlreadyImportedException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureImport
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotReviewableException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownAttemptReferenceException
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ContentSource
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 검토 반영(import): 봉인된(READY_FOR_REVIEW) 초안을 사용자가 확정하면 **신규 ErrorCase** 로 반영한다.
 *
 * 제품 결정(이슈 #144):
 *  - import = **NEW_CASE** (기존 케이스에 병합하지 않음), 항상 **PRIVATE**, 케이스 자동 RESOLVED **금지**(OPEN 생성).
 *  - Attempt → Step(결과→상태 매핑, 순서 보존), SolutionCandidate → Solution(저장된 step id 참조).
 *  - 출처 표식: 케이스에 source=AGENT_IMPORT·reviewer·source_capture_id.
 *  - 잔디/KPI: CASE_CREATED 는 origin=AGENT_IMPORT 로 발행(consumer 게이트가 집계 제외),
 *    **CAPTURE_REVIEWED**(검토 기여, weight 1)만 집계. 전부 한 @Transactional(아웃박스 포함).
 *
 * 멱등: 이미 IMPORTED 면 생성해둔 케이스 id 를 그대로 반환(중복 반영 방지). OPEN/DISCARDED → 409.
 */
@Service
class ImportCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val attempts: CaptureAttemptRepositoryPort,
    private val solutions: CaptureSolutionRepositoryPort,
    private val captureImports: CaptureImportRepositoryPort,
    private val captureArtifacts: CaptureArtifactRepositoryPort,
    private val errorCases: ErrorCaseRepositoryPort,
    private val steps: StepRepositoryPort,
    private val caseSolutions: SolutionRepositoryPort,
    private val createAttachment: CreateAttachmentUseCase,
    private val caseAttachments: ErrorCaseAttachmentRepositoryPort,
    private val attachmentStorage: AttachmentStoragePort,
    private val activity: ActivityEventPublisherPort,
    private val cacheInvalidator: org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: ImportCaptureCommand): ImportResult {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.reviewerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)

        val requestHash = requestHash(cmd)

        // 이미 반영됨: 같은 요청(hash)이면 같은 케이스를 멱등 반환, 다른 요청이면 409 + 기존 caseId 안내.
        if (capture.status == CaptureStatus.IMPORTED) {
            val prior = captureImports.findByCaptureId(capture.id!!)
            val priorCaseId = prior?.targetCaseId ?: capture.importedCaseId
            if (prior != null && prior.requestHash == requestHash) {
                return ImportResult(caseId = priorCaseId!!, alreadyImported = true)
            }
            throw CaptureAlreadyImportedException(capture.captureUuid, priorCaseId)
        }
        if (capture.status != CaptureStatus.READY_FOR_REVIEW) {
            throw CaptureNotReviewableException(capture.captureUuid, capture.status)
        }
        // 검토 시점 revision 일치 확인(봉인 후 편집이 끼면 409 REVISION_CONFLICT).
        capture.requireRevision(cmd.reviewedRevision)

        val now = Instant.now(clock)
        val reviewer = cmd.reviewerUserId
        val captureId = capture.id!!

        // 사용자가 선택한 Attempt 만, 선택 순서대로. 없는 참조는 422.
        val attemptByClientId = attempts.listByCapture(captureId).associateBy { it.clientAttemptId }
        val selectedAttempts = cmd.selectedAttemptIds.map {
            attemptByClientId[it] ?: throw UnknownAttemptReferenceException(cmd.captureUuid, it)
        }

        // 1) 신규 케이스(PRIVATE, OPEN). 자동 RESOLVED 하지 않는다.
        val case = errorCases.save(
            ErrorCase.create(
                ownerUserId = reviewer,
                title = truncate(
                    capture.title?.takeIf { it.isNotBlank() } ?: "Imported capture ${capture.captureUuid}",
                    TITLE_MAX,
                ),
                project = capture.project,
                snapshot = null,
                description = buildDescription(capture.content),
                meta = Meta.create(workspaceId = null, severityCode = null),
                visibility = Visibility.PRIVATE,
                snippets = emptyList(),
                attachments = emptyList(),
                tags = emptyList(),
                occurredAt = null,
                source = ContentSource.AGENT_IMPORT,
                reviewedByUserId = reviewer,
                sourceCaptureId = captureId,
            )
        )
        val caseId = case.id!!

        // 2) 선택 Attempt → Step(선택 순서 보존). clientAttemptId → 저장된 stepId 매핑(Solution 참조 해소용).
        val stepIdByClientAttempt = LinkedHashMap<String, Long>()
        selectedAttempts.forEachIndexed { index, attempt ->
            val step = steps.save(
                Step.create(
                    errorCaseId = caseId,
                    authorUserId = reviewer,
                    orderIndex = index,
                    title = truncate(attempt.detail.title.ifBlank { "Attempt ${index + 1}" }, TITLE_MAX),
                    status = mapStatus(attempt.result),
                    attemptType = attempt.attemptType?.takeIf { it.isNotBlank() }?.let { truncate(it, ATTEMPT_TYPE_MAX) },
                    body = buildStepBody(attempt.detail),
                    insight = attempt.detail.insight?.takeIf { it.isNotBlank() }?.let { truncate(it, INSIGHT_MAX) },
                )
            )
            stepIdByClientAttempt[attempt.clientAttemptId] = step.id!!
        }

        // 3) 선택 SolutionCandidate → Solution. 참조 clientAttemptId 를 (선택된) stepId 로 해소.
        val selectedSolutionIds = cmd.selectedSolutionIds.toSet()
        solutions.listByCapture(captureId).filter { it.clientSolutionId in selectedSolutionIds }.forEach { candidate ->
            val stepIds = candidate.content.clientAttemptIds.mapNotNull { stepIdByClientAttempt[it] }.distinct()
            if (stepIds.isNotEmpty()) {
                caseSolutions.save(
                    Solution.create(
                        errorCaseId = caseId,
                        authorUserId = reviewer,
                        title = truncate(candidate.title, TITLE_MAX),
                        stepIds = stepIds,
                    )
                )
            }
        }

        // 3-b) 선택 근거(선택 Attempt 의 evidenceRefs ∪ selectedArtifactIds)를 Case 첨부로 승격·연결.
        //      캡처 스토리지의 바이트를 읽어 error_case_attachment 로 복사(참조 X) → 케이스에 링크, capture_artifact→LINKED.
        //      (원본 캡처 아티팩트는 capture_artifact 에 보존. 원래 AttemptResult 는 보존된 capture_attempt 에 남음.)
        val wantedArtifactIds = (selectedAttempts.flatMap { it.detail.evidenceRefs } + cmd.selectedArtifactIds).toSet()
        if (wantedArtifactIds.isNotEmpty()) {
            val linkedMarkers = mutableListOf<String>()
            captureArtifacts.listByCapture(captureId)
                .filter { it.clientArtifactId in wantedArtifactIds && it.isCompleted }
                .forEach { art ->
                    val created = createAttachment.invoke(
                        CreateAttachmentCommand(
                            uploaderUserId = reviewer,
                            title = null,
                            caption = null,
                            fileName = art.fileName ?: art.clientArtifactId,
                            contentType = art.contentType,
                            size = art.sizeBytes,
                            bytes = attachmentStorage.getBytes(art.objectKey),
                        )
                    )
                    linkedMarkers += created.markerId
                    art.markLinked()
                    captureArtifacts.save(art)
                }
            if (linkedMarkers.isNotEmpty()) caseAttachments.linkToCase(linkedMarkers, caseId)
        }

        // 4) 사용자가 명시적으로 해결 표시한 경우에만 RESOLVED(OPEN→IN_PROGRESS→RESOLVED). 기본은 OPEN 유지.
        if (cmd.markResolved) {
            case.transitionTo(ErrorCaseStatus.IN_PROGRESS, reviewer)
            case.transitionTo(ErrorCaseStatus.RESOLVED, reviewer)
            errorCases.save(case)
        }

        // 5) 초안 종료(IMPORTED) + 케이스 백링크 + CaptureImport(요청해시·매핑 보존 — 재반영 멱등/충돌 기준).
        capture.markImported(caseId, now)
        captures.save(capture)
        captureImports.save(
            CaptureImport(
                captureId = captureId,
                reviewedRevision = cmd.reviewedRevision,
                reviewerUserId = reviewer,
                reviewedAt = now,
                targetCaseId = caseId,
                requestHash = requestHash,
                attemptStepMap = stepIdByClientAttempt,
                createdAt = now,
            )
        )

        // 5) 활동 이벤트(같은 트랜잭션의 아웃박스).
        //    CASE_CREATED: origin=AGENT_IMPORT → raw 원장엔 남되 잔디/KPI 집계에서 제외(게이트).
        activity.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = reviewer,
                type = ActivityEventPublisherPort.Type.CASE_CREATED,
                occurredAt = now,
                idempotencyKey = "case:$caseId",
                meta = mapOf("visibility" to Visibility.PRIVATE.name, "source" to ContentSource.AGENT_IMPORT.name),
                origin = ActivityEventPublisherPort.Origin.AGENT_IMPORT,
            )
        )
        //    CAPTURE_REVIEWED: origin=USER(기본) → 집계되는 유일한 "검토 기여"(weight 1).
        activity.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = reviewer,
                type = ActivityEventPublisherPort.Type.CAPTURE_REVIEWED,
                occurredAt = now,
                idempotencyKey = "capture-reviewed:$captureId",
                meta = mapOf("caseId" to caseId, "captureUuid" to capture.captureUuid.toString()),
            )
        )

        // 캐시 무효화 — 신규 Case owner 의 recent-active + my-recent-activities (CASE_CREATED 반영, 생성 경로와 대칭).
        runCatching { cacheInvalidator.evictRecentActive(reviewer) }
        runCatching { cacheInvalidator.evictMyRecentActivities(reviewer) }

        return ImportResult(caseId = caseId, alreadyImported = false)
    }

    /** 정규화한 반영 요청 해시 — 같은 선택·revision·markResolved 면 동일(재반영 멱등/충돌 판정). */
    private fun requestHash(cmd: ImportCaptureCommand): String {
        val normalized = buildString {
            append(cmd.reviewedRevision).append('|')
            append(cmd.selectedAttemptIds.sorted().joinToString(",")).append('|')
            append(cmd.selectedSolutionIds.sorted().joinToString(",")).append('|')
            append(cmd.markResolved)
        }
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Attempt 결과 → Step 상태. SUCCEEDED→RESOLVED, FAILED→FAILED, 그 외(진행/불명)→IN_PROGRESS. */
    private fun mapStatus(result: AttemptResult): StepStatus = when (result) {
        AttemptResult.SUCCEEDED -> StepStatus.RESOLVED
        AttemptResult.FAILED -> StepStatus.FAILED
        AttemptResult.PENDING, AttemptResult.INCONCLUSIVE -> StepStatus.IN_PROGRESS
    }

    /** Attempt 의 서사(가설·수행·다음 단계)를 Step 본문 마크다운으로. 전부 비면 null. */
    private fun buildStepBody(detail: AttemptDetail): String? {
        val parts = buildList {
            detail.hypothesis?.takeIf { it.isNotBlank() }?.let { add("**가설**\n$it") }
            detail.action?.takeIf { it.isNotBlank() }?.let { add("**수행**\n$it") }
            detail.nextStep?.takeIf { it.isNotBlank() }?.let { add("**다음 단계**\n$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Capture 서사(증상·사실·가설·재현·환경·미확인)를 케이스 본문 마크다운으로. 전부 비면 null. */
    private fun buildDescription(content: CaptureContent): String? {
        val sections = buildList {
            content.symptom?.takeIf { it.isNotBlank() }?.let { add("## 증상\n$it") }
            content.observedFacts.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { facts -> add("## 관측된 사실\n" + facts.joinToString("\n") { "- $it" }) }
            content.hypotheses.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { hs -> add("## 가설\n" + hs.joinToString("\n") { "- $it" }) }
            content.reproduction?.takeIf { it.steps.isNotEmpty() }?.let { repro ->
                val head = if (repro.executed) "## 재현 절차 (실행 확인됨)" else "## 재현 절차 (제안, 미실행)"
                add(head + "\n" + repro.steps.joinToString("\n") { "- $it" })
            }
            content.environment?.let { env ->
                val lines = buildList {
                    env.service?.let { add("- service: $it") }
                    env.branch?.let { add("- branch: $it") }
                    env.commitSha?.let { add("- commit: $it") }
                    env.dirtyWorktree?.let { add("- dirtyWorktree: $it") }
                }
                if (lines.isNotEmpty()) add("## 환경\n" + lines.joinToString("\n"))
            }
            content.unknowns.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { us -> add("## 아직 확인되지 않음\n" + us.joinToString("\n") { "- $it" }) }
        }
        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max)

    data class ImportResult(val caseId: Long, val alreadyImported: Boolean)

    companion object {
        private const val TITLE_MAX = 200
        private const val ATTEMPT_TYPE_MAX = 64
        private const val INSIGHT_MAX = 500
    }
}

data class ImportCaptureCommand(
    val captureUuid: UUID,
    val reviewerUserId: Long,
    val reviewedRevision: Int,
    /** 반영할 Attempt(clientAttemptId), 사용자 지정 순서 = Step 순서. */
    val selectedAttemptIds: List<String> = emptyList(),
    val selectedSolutionIds: List<String> = emptyList(),
    /** P0: 저장만(artifact→Case 연결은 후속). */
    val selectedArtifactIds: List<String> = emptyList(),
    /** 사용자가 명시적으로 해결로 표시(기본 false — 자동 RESOLVED 금지). */
    val markResolved: Boolean = false,
    val note: String? = null,
)

/**
 * 초안 폐기(DISCARDED). OPEN·READY_FOR_REVIEW 에서 가능, 이미 DISCARDED 면 멱등, IMPORTED 는 409.
 * 본인 소유만(아니면 404).
 */
@Service
class DiscardCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: DiscardCaptureCommand): AgentCapture {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.reviewerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.discard(Instant.now(clock))
        return captures.save(capture)
    }
}

data class DiscardCaptureCommand(
    val captureUuid: UUID,
    val reviewerUserId: Long,
)

/** 검토 큐 조회 — 소유자의 봉인된(READY_FOR_REVIEW) 초안 목록(최근 갱신순). */
@Service
class ListCapturesForReviewUseCase(
    private val captures: AgentCaptureRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long): List<AgentCapture> =
        captures.listByOwnerAndStatus(ownerUserId, CaptureStatus.READY_FOR_REVIEW)
}
