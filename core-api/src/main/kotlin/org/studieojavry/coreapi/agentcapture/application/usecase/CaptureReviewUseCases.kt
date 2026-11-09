package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotReviewableException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ContentSource
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
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
    private val errorCases: ErrorCaseRepositoryPort,
    private val steps: StepRepositoryPort,
    private val caseSolutions: SolutionRepositoryPort,
    private val activity: ActivityEventPublisherPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: ImportCaptureCommand): ImportResult {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.reviewerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)

        // 멱등: 이미 반영됨 → 생성해둔 케이스를 그대로 반환.
        if (capture.status == CaptureStatus.IMPORTED) {
            return ImportResult(caseId = capture.importedCaseId!!, alreadyImported = true)
        }
        if (capture.status != CaptureStatus.READY_FOR_REVIEW) {
            throw CaptureNotReviewableException(capture.captureUuid, capture.status)
        }

        val now = Instant.now(clock)
        val reviewer = cmd.reviewerUserId
        val captureId = capture.id!!

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

        // 2) Attempt → Step(순서 보존). clientAttemptId → 저장된 stepId 매핑(Solution 참조 해소용).
        val stepIdByClientAttempt = LinkedHashMap<String, Long>()
        attempts.listByCapture(captureId).forEachIndexed { index, attempt ->
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

        // 3) SolutionCandidate → Solution. 참조 clientAttemptId 를 저장된 stepId 로 해소(순서·유일 보존).
        solutions.listByCapture(captureId).forEach { candidate ->
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

        // 4) 초안 종료(IMPORTED) + 케이스 백링크.
        capture.markImported(caseId, now)
        captures.save(capture)

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

        return ImportResult(caseId = caseId, alreadyImported = false)
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
