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
import org.studieojavry.coreapi.agentcapture.domain.model.UnexecutedVerificationPassedException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownArtifactReferenceException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownAttemptReferenceException
import org.studieojavry.coreapi.agentcapture.domain.model.UnknownSolutionReferenceException
import org.studieojavry.coreapi.agentcapture.domain.model.Verification
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
import org.studieojavry.coreapi.errorcase.solution.domain.model.SolutionVerification
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

        // 검토 중 수정 대상은 반드시 "선택된" 항목이어야 한다(미선택/미존재 키 → 422).
        val selectedAttemptIdSet = cmd.selectedAttemptIds.toSet()
        cmd.stepEdits.keys.firstOrNull { it !in selectedAttemptIdSet }?.let {
            throw UnknownAttemptReferenceException(cmd.captureUuid, it)
        }
        val selectedSolutionIdSet = cmd.selectedSolutionIds.toSet()
        cmd.solutionEdits.keys.firstOrNull { it !in selectedSolutionIdSet }?.let {
            throw UnknownSolutionReferenceException(cmd.captureUuid, it)
        }

        // 선택한 Solution/Artifact 도 Attempt 와 동일하게 "이 캡처에 실제 존재" 엄격 검증(미존재 → 422).
        // (과거엔 silent filter 로 무시 → 오타·잘못된 id 가 조용히 누락됐다. 이제 명시적으로 거부.)
        val solutionCandidates = solutions.listByCapture(captureId)
        val candidateByClientId = solutionCandidates.associateBy { it.clientSolutionId }
        cmd.selectedSolutionIds.forEach {
            if (it !in candidateByClientId) throw UnknownSolutionReferenceException(cmd.captureUuid, it)
        }
        val captureArtifactList = captureArtifacts.listByCapture(captureId)
        val artifactByClientId = captureArtifactList.associateBy { it.clientArtifactId }
        cmd.selectedArtifactIds.forEach {
            val art = artifactByClientId[it] ?: throw UnknownArtifactReferenceException(cmd.captureUuid, it)
            if (!art.isCompleted) throw UnknownArtifactReferenceException(cmd.captureUuid, it)
        }

        // 1) 신규 케이스(PRIVATE, OPEN). 자동 RESOLVED 하지 않는다.
        //    검토 중 수정: editedTitle/editedDescription 이 있으면 캡처 원본 대신 사용.
        val case = errorCases.save(
            ErrorCase.create(
                ownerUserId = reviewer,
                title = truncate(
                    cmd.editedTitle?.takeIf { it.isNotBlank() }
                        ?: capture.title?.takeIf { it.isNotBlank() }
                        ?: "Imported capture ${capture.captureUuid}",
                    TITLE_MAX,
                ),
                project = capture.project,
                snapshot = null,
                description = if (cmd.editedDescription != null) cmd.editedDescription.ifBlank { null }
                    else buildDescription(capture.content),
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

        // 2-pre) 반영할 근거 = selectedArtifactIds (명시 선택 집합). Attempt 선택만으로 근거가 자동 포함되지 않으며,
        //        사용자가 개별 근거를 제외할 수 있다(민감/노이즈 파일). FE 는 선택 Attempt 의 evidenceRefs 를 기본 포함해 보낸다.
        //        선택 근거를 Case 첨부로 승격·연결하고, Step 본문 토큰 삽입용 clientArtifactId→새 markerId 매핑을 확보한다.
        //        (존재·완료는 위에서 엄격 검증됨. 캡처 바이트를 error_case_attachment 로 복사, capture_artifact→LINKED.)
        val selectedArtifactIdSet = cmd.selectedArtifactIds.toSet()
        val markerIdByClientArtifact = LinkedHashMap<String, String>()
        if (selectedArtifactIdSet.isNotEmpty()) {
            captureArtifactList
                .filter { it.clientArtifactId in selectedArtifactIdSet }
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
                    markerIdByClientArtifact[art.clientArtifactId] = created.markerId
                    art.markLinked()
                    captureArtifacts.save(art)
                }
            if (markerIdByClientArtifact.isNotEmpty()) {
                caseAttachments.linkToCase(markerIdByClientArtifact.values.toList(), caseId)
            }
        }

        // 2) 선택 Attempt → Step(선택 순서 보존). clientAttemptId → 저장된 stepId 매핑(Solution 참조 해소용).
        //    각 Attempt 의 근거(evidenceRefs) 중 승격된 것의 @attach 토큰을 그 Step 본문에 삽입한다 — 근거가
        //    "원래 나온 시도(Step)" 아래 인라인으로 붙는다. 한 근거는 그것을 처음 참조한 Step 에만 귀속(중복 방지).
        //    어떤 Attempt 에도 안 매인 근거(selectedArtifactIds 단독)는 토큰이 없어 '관련 첨부' 갤러리에 남는다.
        val stepIdByClientAttempt = LinkedHashMap<String, Long>()
        val assignedArtifacts = HashSet<String>()
        selectedAttempts.forEachIndexed { index, attempt ->
            val edit = cmd.stepEdits[attempt.clientAttemptId]
            val baseBody = if (edit?.body != null) edit.body.ifBlank { null } else buildStepBody(attempt.detail)
            val evidenceTokens = attempt.detail.evidenceRefs
                .filter { it in markerIdByClientArtifact && assignedArtifacts.add(it) }
                .map { "@attach(${markerIdByClientArtifact[it]})" }
                .filter { token -> baseBody?.contains(token) != true }
            val step = steps.save(
                Step.create(
                    errorCaseId = caseId,
                    authorUserId = reviewer,
                    orderIndex = index,
                    title = truncate(
                        edit?.title?.takeIf { it.isNotBlank() }
                            ?: attempt.detail.title.ifBlank { "Attempt ${index + 1}" },
                        TITLE_MAX,
                    ),
                    // 최종 Step 상태: 사용자가 정정했으면 그 값, 아니면 캡처 Attempt 결과서 파생.
                    status = edit?.status ?: mapStatus(attempt.result),
                    attemptType = attempt.attemptType?.takeIf { it.isNotBlank() }?.let { truncate(it, ATTEMPT_TYPE_MAX) },
                    body = appendEvidenceTokens(baseBody, evidenceTokens),
                    insight = attempt.detail.insight?.takeIf { it.isNotBlank() }?.let { truncate(it, INSIGHT_MAX) },
                )
            )
            stepIdByClientAttempt[attempt.clientAttemptId] = step.id!!
        }

        // 3) 선택 SolutionCandidate → Solution. 참조 clientAttemptId 를 (선택된) stepId 로 해소.
        //    후보의 상세 설명(description)·검증 근거(verification)도 함께 이관한다.
        solutionCandidates.filter { it.clientSolutionId in selectedSolutionIdSet }.forEach { candidate ->
            val stepIds = candidate.content.clientAttemptIds.mapNotNull { stepIdByClientAttempt[it] }.distinct()
            if (stepIds.isNotEmpty()) {
                val edit = cmd.solutionEdits[candidate.clientSolutionId]
                // 최종 검증 정보: 전체 제외(clearVerification) > 사용자 정정(verification) > 캡처 원본.
                val resolvedVerification = when {
                    edit?.clearVerification == true -> null
                    edit?.verification != null -> edit.verification
                    else -> candidate.content.verification?.let { toSolutionVerification(it) }
                }
                // 정책: 실제 실행하지 않은(executed=false) 검증은 PASSED 로 확정 금지.
                resolvedVerification?.let { requireNotUnexecutedPass(it, capture.captureUuid) }
                caseSolutions.save(
                    Solution.create(
                        errorCaseId = caseId,
                        authorUserId = reviewer,
                        title = truncate(edit?.title?.takeIf { it.isNotBlank() } ?: candidate.title, TITLE_MAX),
                        stepIds = stepIds,
                        description = if (edit?.description != null) edit.description.ifBlank { null }
                            else candidate.content.description?.takeIf { it.isNotBlank() },
                        verification = resolvedVerification,
                    )
                )
            }
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

    /**
     * 정규화한 반영 요청 해시 — 같은 선택·revision·markResolved·검토수정 이면 동일(재반영 멱등/충돌 판정).
     * 편집값이 다르면 해시가 달라져 재반영이 멱등 재요청이 아닌 409 충돌로 처리된다.
     */
    private fun requestHash(cmd: ImportCaptureCommand): String {
        val normalized = buildString {
            append(cmd.reviewedRevision).append('|')
            append(cmd.selectedAttemptIds.sorted().joinToString(",")).append('|')
            append(cmd.selectedSolutionIds.sorted().joinToString(",")).append('|')
            append(cmd.markResolved).append('|')
            append(cmd.editedTitle ?: "").append('|')
            append(cmd.editedDescription ?: "").append('|')
            append(cmd.stepEdits.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value.title ?: ""}:${it.value.body ?: ""}:${it.value.status ?: ""}" }).append('|')
            append(cmd.solutionEdits.entries.sortedBy { it.key }.joinToString(",") {
                val vrepr = if (it.value.clearVerification) "CLEAR"
                    else it.value.verification?.let { v -> objectMapper.writeValueAsString(v) } ?: ""
                "${it.key}=${it.value.title ?: ""}:${it.value.description ?: ""}:$vrepr"
            })
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

    /** Step 본문 끝에 근거 @attach 토큰 블록을 덧붙인다. 본문이 비면 토큰만. 토큰이 없으면 원본 유지. */
    private fun appendEvidenceTokens(baseBody: String?, tokens: List<String>): String? {
        if (tokens.isEmpty()) return baseBody
        val block = tokens.joinToString("\n")
        return if (baseBody.isNullOrBlank()) block else "$baseBody\n\n$block"
    }

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max)

    /**
     * 정책: 실제 실행하지 않은(executed=false) 검증을 PASSED 로 확정하지 않는다.
     * 반영(확정) 시점에 걸러, AI 가 실행 없이 PASSED 로 표기한 신뢰할 수 없는 검증이 정식 Case 로 넘어가는 것을 막는다.
     * (사용자는 executed=true 로 정정하거나, result 를 바꾸거나, clearVerification 으로 검증을 제외할 수 있다.)
     */
    private fun requireNotUnexecutedPass(v: SolutionVerification, captureUuid: UUID) {
        if (v.result == "PASSED" && !v.executed) {
            throw UnexecutedVerificationPassedException(captureUuid)
        }
    }

    /** 캡처 후보의 Verification → Case Solution 의 SolutionVerification(errorcase 값 타입)으로 변환. */
    private fun toSolutionVerification(v: Verification): SolutionVerification = SolutionVerification(
        result = v.result.name,
        scope = v.scope?.name,
        command = v.command,
        testId = v.testId,
        executed = v.executed,
        ranAt = v.ranAt,
        commitSha = v.commitSha,
        evidence = v.evidence,
    )

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
    /** 반영할 근거(clientArtifactId). 선택 Attempt 의 evidenceRefs 와 합쳐 Case 첨부로 승격·연결된다. */
    val selectedArtifactIds: List<String> = emptyList(),
    /** 사용자가 명시적으로 해결로 표시(기본 false — 자동 RESOLVED 금지). */
    val markResolved: Boolean = false,
    /** 검토 중 수정: Case 제목 override(null 이면 캡처 제목). */
    val editedTitle: String? = null,
    /** 검토 중 수정: Case 본문 override(null 이면 캡처 서사, 빈 문자열이면 본문 없음). */
    val editedDescription: String? = null,
    /** 검토 중 수정: Step(=선택 Attempt)별 제목·본문 override. key=clientAttemptId(선택 Attempt 만 허용). */
    val stepEdits: Map<String, StepEdit> = emptyMap(),
    /** 검토 중 수정: Solution(=선택 후보)별 제목·설명 override. key=clientSolutionId(선택 후보만 허용). */
    val solutionEdits: Map<String, SolutionEdit> = emptyMap(),
    val note: String? = null,
)

/** 검토 중 Step 수정. null 필드는 캡처 원본 유지. status 는 캡처 Attempt 결과서 파생된 기본 상태 정정용. */
data class StepEdit(
    val title: String? = null,
    val body: String? = null,
    val status: StepStatus? = null,
)

/**
 * 검토 중 Solution 수정. null 필드는 캡처 원본 유지.
 *  - verification: 캡처 검증 정보 정정용(통째 교체).
 *  - clearVerification=true: AI 검증 정보를 신뢰할 수 없을 때 verification=null 로 반영(전체 제외). verification 보다 우선.
 */
data class SolutionEdit(
    val title: String? = null,
    val description: String? = null,
    val verification: SolutionVerification? = null,
    val clearVerification: Boolean = false,
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
