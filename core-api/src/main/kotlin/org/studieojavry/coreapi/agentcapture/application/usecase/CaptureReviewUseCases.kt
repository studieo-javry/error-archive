package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureImportRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBindingNotFoundException
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
import org.studieojavry.coreapi.agentcapture.domain.model.ImportedStepBodyMarkerException
import org.studieojavry.coreapi.agentcapture.domain.model.InvalidVerificationException
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionWithoutSelectedAttemptException
import org.studieojavry.coreapi.agentcapture.domain.model.UnexecutedVerificationPassedException
import org.studieojavry.coreapi.agentcapture.domain.model.VerificationEvidenceRequiredException
import org.studieojavry.coreapi.agentcapture.domain.model.VerificationResult
import org.studieojavry.coreapi.agentcapture.domain.model.VerificationScope
import org.studieojavry.coreapi.errorcase.shared.application.usecase.MarkerTokens
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
    private val projectBindings: ProjectBindingRepositoryPort,
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

        // 귀속할 ProjectBinding — 세 상태를 구분(확인·정정·해제; PR-Y):
        //  - clearProjectBinding=true → **명시 해제**(null). capture 상속값이 있어도 프로젝트 없이 반영.
        //  - projectBindingId 지정 → **정정**. 본인 소유여야 함(없거나 타인 → 404, 존재 노출 회피).
        //  - 둘 다 없음 → **확인(상속)**. capture 상속값 사용, 단 무효(삭제됨/타인)면 미귀속(404 아님).
        val effectiveProjectBindingId: Long? = when {
            cmd.clearProjectBinding -> null
            cmd.projectBindingId != null -> {
                val bid = cmd.projectBindingId
                val binding = projectBindings.findById(bid)
                if (binding == null || binding.ownerUserId != cmd.reviewerUserId) throw ProjectBindingNotFoundException(bid)
                bid
            }
            else -> capture.projectBindingId?.let { bid ->
                val binding = projectBindings.findById(bid)
                if (binding != null && binding.ownerUserId == cmd.reviewerUserId) bid else null
            }
        }

        val now = Instant.now(clock)
        val reviewer = cmd.reviewerUserId
        val captureId = capture.id!!

        // 사용자가 선택한 Attempt 만, 선택 순서대로. 중복 선택은 distinct(첫 등장 순서 유지)로 Step 중복 생성 방지.
        // 없는 참조는 422.
        val attemptByClientId = attempts.listByCapture(captureId).associateBy { it.clientAttemptId }
        val selectedAttempts = cmd.selectedAttemptIds.distinct().map {
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
                projectBindingId = effectiveProjectBindingId,
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
        // 반영될 Step 본문의 @attach 는 이번에 승격·연결된 첨부만, @snippet 은 불가(import 는 스니펫 미생성).
        // 일반 Step API 의 Case-소속 검증과 일관 — 사용자 수정 본문도 우회 못 하게.
        val promotedMarkers = markerIdByClientArtifact.values.toSet()
        selectedAttempts.forEachIndexed { index, attempt ->
            val edit = cmd.stepEdits[attempt.clientAttemptId]
            val baseBody = if (edit?.body != null) edit.body.ifBlank { null } else buildStepBody(attempt.detail)
            val evidenceTokens = attempt.detail.evidenceRefs
                .filter { it in markerIdByClientArtifact && assignedArtifacts.add(it) }
                .map { "@attach(${markerIdByClientArtifact[it]})" }
                .filter { token -> baseBody?.contains(token) != true }
            val body = appendEvidenceTokens(baseBody, evidenceTokens)
            validateImportedStepBodyMarkers(body, promotedMarkers, cmd.captureUuid)
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
                    body = body,
                    insight = attempt.detail.insight?.takeIf { it.isNotBlank() }?.let { truncate(it, INSIGHT_MAX) },
                )
            )
            stepIdByClientAttempt[attempt.clientAttemptId] = step.id!!
        }

        // 3) 선택 SolutionCandidate → Solution. 참조 clientAttemptId 를 (선택된) stepId 로 해소.
        //    후보의 상세 설명(description)·검증 근거(verification)도 함께 이관한다.
        solutionCandidates.filter { it.clientSolutionId in selectedSolutionIdSet }.forEach { candidate ->
            val stepIds = candidate.content.clientAttemptIds.mapNotNull { stepIdByClientAttempt[it] }.distinct()
            // 부분 참조는 허용하되, 참조 Step 이 하나도 없으면 명시 선택을 조용히 누락하지 않고 거부(422).
            if (stepIds.isEmpty()) {
                throw SolutionWithoutSelectedAttemptException(cmd.captureUuid, candidate.clientSolutionId)
            }
            run {
                val edit = cmd.solutionEdits[candidate.clientSolutionId]
                // 최종 검증 정보: 전체 제외(clearVerification) > 사용자 정정(verification) > 캡처 원본.
                val resolvedVerification = when {
                    edit?.clearVerification == true -> null
                    edit?.verification != null -> edit.verification
                    else -> candidate.content.verification?.let { toSolutionVerification(it) }
                }
                // 정책: result/scope 코드 검증 + 실행 안 한 PASSED 금지 + PASSED 근거 필수(캡처측 규칙과 일관).
                resolvedVerification?.let { validateVerification(it, capture.captureUuid) }
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
        // 반영 결과에 영향을 주는 모든 필드를 정규화해 JSON 직렬화 후 SHA-256.
        //  - selectedAttemptIds 는 **순서 보존**(Step 순서라 결과에 영향). selectedSolutionIds/selectedArtifactIds 는
        //    결과에 순서 무관이라 정렬해 안정화.
        //  - editedTitle/Description, stepEdits.body 등은 null(원본 유지)과 ""(비움)이 결과가 달라 JSON 이 그대로 구분.
        //  - stepEdits/solutionEdits 는 key 정렬(값은 data class 그대로 — status/verification 포함).
        //  - projectBindingId: 명시 귀속 대상이 달라지면 결과가 달라지므로 해시에 포함.
        val normalized = linkedMapOf<String, Any?>(
            "reviewedRevision" to cmd.reviewedRevision,
            "selectedAttemptIds" to cmd.selectedAttemptIds.distinct(),
            "selectedSolutionIds" to cmd.selectedSolutionIds.sorted(),
            "selectedArtifactIds" to cmd.selectedArtifactIds.sorted(),
            "markResolved" to cmd.markResolved,
            "editedTitle" to cmd.editedTitle,
            "editedDescription" to cmd.editedDescription,
            "stepEdits" to cmd.stepEdits.toSortedMap(),
            "solutionEdits" to cmd.solutionEdits.toSortedMap(),
            "projectBindingId" to cmd.projectBindingId,
            "clearProjectBinding" to cmd.clearProjectBinding,
        )
        val json = objectMapper.writeValueAsString(normalized)
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
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
                    env.diffRef?.let { add("- diffRef: $it") }
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
     * 반영될 Step 본문의 마커 검증(일반 Step API 와 일관): @attach 는 이번에 승격된 첨부만, @snippet 은 불가.
     */
    private fun validateImportedStepBodyMarkers(body: String?, promoted: Set<String>, captureUuid: UUID) {
        if (body.isNullOrBlank()) return
        MarkerTokens.attachIds(body).firstOrNull { it !in promoted }
            ?.let { throw ImportedStepBodyMarkerException(captureUuid, it) }
        MarkerTokens.snippetIds(body).firstOrNull()
            ?.let { throw ImportedStepBodyMarkerException(captureUuid, "@snippet($it)") }
    }

    /**
     * 반영(확정)될 Verification 검증. 사용자 정정값·캡처 원본 모두에 적용한다.
     *  - result/scope 는 허용 코드(enum)만.
     *  - 실행하지 않은(executed=false) PASSED 금지 — 신뢰할 수 없는 "통과" 확정 방지.
     *  - PASSED 는 근거(command·testId·evidence) 최소 1개 필요 — 캡처 Solution 후보 규칙과 일관.
     *  사용자는 정정하거나 clearVerification 으로 검증을 제외할 수 있다.
     */
    private fun validateVerification(v: SolutionVerification, captureUuid: UUID) {
        if (v.result != null && VerificationResult.entries.none { it.name == v.result }) {
            throw InvalidVerificationException(captureUuid, "result='${v.result}'")
        }
        if (v.scope != null && VerificationScope.entries.none { it.name == v.scope }) {
            throw InvalidVerificationException(captureUuid, "scope='${v.scope}'")
        }
        if (v.result == VerificationResult.PASSED.name) {
            if (!v.executed) throw UnexecutedVerificationPassedException(captureUuid)
            if (v.command.isNullOrBlank() && v.testId.isNullOrBlank() && v.evidence.isNullOrBlank()) {
                throw VerificationEvidenceRequiredException(captureUuid)
            }
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
    /** 생성될 Case 를 귀속시킬 ProjectBinding id(본인 소유). 미지정 시 capture 상속값(= 확인). 정정 시 지정. */
    val projectBindingId: Long? = null,
    /** 명시적 프로젝트 해제 — true 면 capture 상속값이 있어도 미귀속(null)으로 반영. projectBindingId 보다 우선. */
    val clearProjectBinding: Boolean = false,
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

/**
 * 검토 큐 조회 — 소유자의 봉인된(READY_FOR_REVIEW) 초안을 keyset(cursor)로 한 페이지.
 * 정렬 `updatedAt DESC, id DESC`. cursor 생략 = 첫 페이지, 응답의 nextCursor 를 다음 cursor 로.
 */
@Service
class ListCapturesForReviewUseCase(
    private val captures: AgentCaptureRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long, cursor: String?, size: Int): CapturePage =
        CaptureCursor.page(cursor, size) { cur, limit ->
            captures.pageByOwnerAndStatuses(
                ownerUserId, listOf(CaptureStatus.READY_FOR_REVIEW), cur?.first, cur?.second, limit,
            )
        }
}

/**
 * 자동기록 계측 — 최근 창(window) 내 생성된 초안의 상태 퍼널. 성공률(자동 트리거)은 모델 행동이라 서버가
 * 못 보지만, 사용자 검토 결과로 **오생성률(discarded/reviewed)** 과 **채택률(imported/reviewed)** 을 집계한다.
 * 읽기 전용·소유자 범위. 스키마 변경 없이 상태 집계로만 계산한다.
 */
@Service
class CaptureMetricsUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long, windowDays: Int): CaptureMetrics {
        val days = windowDays.coerceIn(1, 365)
        val since = Instant.now(clock).minusSeconds(days.toLong() * 86_400L)
        val byStatus = captures.countByOwnerSinceByStatus(ownerUserId, since)
        fun n(s: CaptureStatus) = byStatus[s] ?: 0L
        val open = n(CaptureStatus.OPEN)
        val ready = n(CaptureStatus.READY_FOR_REVIEW)
        val imported = n(CaptureStatus.IMPORTED)
        val discarded = n(CaptureStatus.DISCARDED)
        val reviewed = imported + discarded
        return CaptureMetrics(
            windowDays = days,
            since = since,
            created = open + ready + imported + discarded,
            open = open,
            readyForReview = ready,
            imported = imported,
            discarded = discarded,
            reviewed = reviewed,
            discardRate = if (reviewed > 0) discarded.toDouble() / reviewed else null,
            adoptionRate = if (reviewed > 0) imported.toDouble() / reviewed else null,
        )
    }
}

data class CaptureMetrics(
    val windowDays: Int,
    val since: Instant,
    val created: Long,
    val open: Long,
    val readyForReview: Long,
    val imported: Long,
    val discarded: Long,
    val reviewed: Long,
    val discardRate: Double?,
    val adoptionRate: Double?,
)

/**
 * 검토 완료 이력 조회 — 종단 상태(IMPORTED·DISCARDED) 초안을 keyset(cursor)로 한 페이지.
 * status 지정 시 그 상태만, 없으면 둘 다. 종단 상태가 아닌 값 요청은 거부(400).
 * 정렬 `updatedAt DESC, id DESC`. 단조 증가하는 이력을 cursor + LIMIT 으로 페이지네이션한다.
 */
@Service
class ListCaptureHistoryUseCase(
    private val captures: AgentCaptureRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long, status: CaptureStatus?, cursor: String?, size: Int): CapturePage {
        require(status == null || status.isTerminal) { "history status must be IMPORTED or DISCARDED" }
        val statuses = status?.let { listOf(it) } ?: listOf(CaptureStatus.IMPORTED, CaptureStatus.DISCARDED)
        return CaptureCursor.page(cursor, size) { cur, limit ->
            captures.pageByOwnerAndStatuses(ownerUserId, statuses, cur?.first, cur?.second, limit)
        }
    }
}

/** keyset cursor 페이지 결과 — items(요청 size 이하) + 다음 페이지 cursor/hasNext. */
data class CapturePage(
    val items: List<AgentCapture>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

/**
 * 자동기록 초안 목록의 keyset cursor 코덱/페이저. cursor = base64url("updatedAt|id").
 * ListErrorCasesUseCase 와 같은 패턴(size+1 조회로 hasNext 판정). 잘못된 cursor 는 무시(처음부터).
 */
object CaptureCursor {
    private const val MAX_SIZE = 100
    private const val DEFAULT_SIZE = 20

    /** fetch(cursor, limit) = 정렬된 결과를 limit 만큼. size 는 1..100 클램프, 기본 20. */
    fun page(
        cursor: String?,
        size: Int,
        fetch: (Pair<Instant, Long>?, Int) -> List<AgentCapture>,
    ): CapturePage {
        val limit = size.takeIf { it > 0 }?.coerceIn(1, MAX_SIZE) ?: DEFAULT_SIZE
        val rows = fetch(decode(cursor), limit + 1)
        val hasNext = rows.size > limit
        val items = if (hasNext) rows.take(limit) else rows
        val nextCursor = items.lastOrNull()
            ?.takeIf { hasNext }
            ?.let { encode(it.updatedAt, it.id!!) }
        return CapturePage(items = items, nextCursor = nextCursor, hasNext = hasNext)
    }

    private fun encode(updatedAt: Instant, id: Long): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$updatedAt|$id".toByteArray(Charsets.UTF_8))

    private fun decode(cursor: String?): Pair<Instant, Long>? {
        if (cursor.isNullOrBlank()) return null
        return runCatching {
            val raw = String(java.util.Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val sep = raw.lastIndexOf('|')
            Instant.parse(raw.substring(0, sep)) to raw.substring(sep + 1).toLong()
        }.getOrNull()
    }
}

/**
 * 폐기 복원 — DISCARDED 초안을 READY_FOR_REVIEW 로 되돌린다. 본인 소유만(아니면 404),
 * DISCARDED 아닌 상태는 409(CaptureNotRestorableException).
 */
@Service
class RestoreCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: RestoreCaptureCommand): AgentCapture {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.reviewerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.restore(Instant.now(clock))
        return captures.save(capture)
    }
}

data class RestoreCaptureCommand(
    val captureUuid: UUID,
    val reviewerUserId: Long,
)
