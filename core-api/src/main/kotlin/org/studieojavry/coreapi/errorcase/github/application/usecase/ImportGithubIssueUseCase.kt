package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.case.application.command.CreateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.CreateErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink.LinkSource
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueCaseDraft
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubUrl
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort.WorkspaceRole
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.command.CreateStepCommand
import org.studieojavry.coreapi.errorcase.step.application.usecase.CreateStepUseCase
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * GitHub Issue 로 Case 만들기 — **미리보기 → 사용자가 확인·수정 → 생성(OPEN)**. (설계 D1: DRAFT 대신 미리보기)
 *
 * Case 범위는 사용자가 고르는 게 아니라 **레포가 속한 설치 범위로 정해진다**(범위 일치 규칙):
 *  - 워크스페이스 설치 → 그 워크스페이스 Case. 요청자는 그 워크스페이스 WRITE+
 *  - 개인 설치          → 개인 Case. 요청자 = 설치를 연결한 본인
 *
 * 생성은 기존 [CreateErrorCaseUseCase] 를 그대로 호출(권한·태그·스냅샷 추출·잔디·캐시 재사용)하고
 * 같은 트랜잭션에서 사용자가 고른 심각도 반영 → 우회 방법 Step(선택) → Issue 연결(IMPORTED)을 기록.
 * 이어서 트랜잭션 밖에서 닫는 PR 소급 부착.
 *
 * 초안 규칙([GithubIssueCaseDraft]): 태그·심각도·발생 시각은 **자동으로 넣지 않고 후보로만** 돌려준다.
 */
@Service
@ConditionalOnGithubEnabled
class ImportGithubIssueUseCase(
    private val scopeGuard: GithubScopeGuard,
    private val githubApi: GithubAppApiPort,
    private val workspaceQuery: WorkspaceQueryPort,
    private val extractor: ErrorSnaphostExtractorPort,
    private val createErrorCase: CreateErrorCaseUseCase,
    private val createStep: CreateStepUseCase,
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val evidence: GithubEvidenceRepositoryPort,
    private val recorder: GithubEvidenceRecorder,
    private val backfiller: IssuePullRequestBackfiller,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    // ── 미리보기 ─────────────────────────────────────────────
    data class Preview(
        val draft: GithubIssueCaseDraft,
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val workspaceId: Long?,
        val visibilityOptions: List<Visibility>,
        val defaultVisibility: Visibility,
        val repoFullName: String,
        val repoIsPrivate: Boolean,
        val issueNumber: Int,
        val issueUrl: String,
        val issueState: String,
        val alreadyLinkedCases: List<LinkedCase>,
    )

    data class LinkedCase(val id: Long, val title: String)

    fun preview(userId: Long, rawUrl: String, requestedWorkspaceId: Long?): Preview {
        val resolved = resolve(userId, rawUrl, requestedWorkspaceId)
        val extracted = resolved.draft.paste?.let { extractor.extract(it) }
        return Preview(
            draft = resolved.draft,
            exceptionClass = extracted?.exceptionClass,
            exceptionMessage = extracted?.exceptionMessage,
            workspaceId = resolved.workspaceId,
            visibilityOptions = resolved.visibilityOptions,
            defaultVisibility = resolved.defaultVisibility,
            repoFullName = resolved.repository.fullName,
            repoIsPrivate = resolved.repository.isPrivate,
            issueNumber = resolved.item.snapshot.number,
            issueUrl = resolved.item.snapshot.url,
            issueState = resolved.item.snapshot.state,
            alreadyLinkedCases = alreadyLinkedCases(userId, resolved.issueRef),
        )
    }

    // ── 생성 ────────────────────────────────────────────────
    /** null = 초안 값(태그는 빈 목록, 심각도·발생 시각은 비움, 우회 Step 은 만들지 않음). */
    data class Command(
        val userId: Long,
        val url: String,
        val workspaceId: Long?,
        val visibility: Visibility?,
        val title: String?,
        val description: String?,
        val paste: String?,
        val tags: List<String>?,
        val severity: Int? = null,
        val occurredAt: Instant? = null,
        val includeWorkaroundStep: Boolean = false,
    )

    data class Created(
        val caseId: Long, val title: String, val status: String, val linkId: Long, val backfilledPullRequests: Int,
        val workaroundStepId: Long?,
    )

    fun create(command: Command): Created {
        val resolved = resolve(command.userId, command.url, command.workspaceId)
        val visibility = command.visibility ?: resolved.defaultVisibility
        if (visibility !in resolved.visibilityOptions) {
            throw GithubImportVisibilityException(
                if (visibility == Visibility.PUBLIC && resolved.repository.isPrivate) "비공개 저장소의 Issue 로는 공개(PUBLIC) Case 를 만들 수 없어요"
                else "이 Case 에 쓸 수 없는 공개 범위: $visibility (가능: ${resolved.visibilityOptions})",
            )
        }
        val draft = resolved.draft
        if (command.includeWorkaroundStep && draft.workaroundStep == null) {
            throw GithubImportInvalidRequestException("이 Issue 에는 우회 방법(Workaround) 섹션이 없어요")
        }
        if (command.severity != null && command.severity !in 1..4) {
            throw GithubImportInvalidRequestException("severity 는 1(Outage)~4(Info) 중 하나예요")
        }
        val result = tx.execute {
            val created = createErrorCase.invoke(
                CreateErrorCaseCommand(
                    userId = command.userId,
                    title = command.title?.takeIf { it.isNotBlank() } ?: draft.title,
                    project = null,
                    paste = command.paste ?: draft.paste,
                    description = command.description ?: draft.description,
                    snippetMarkerIds = emptyList(),
                    attachmentMarkerIds = emptyList(),
                    workspaceId = resolved.workspaceId,
                    tags = command.tags ?: draft.tags,
                    occurredAt = command.occurredAt?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) },
                    visibility = visibility,
                ),
            )
            // 생성 API 가 심각도를 받지 않아서 같은 트랜잭션에서 바로 반영(사용자가 고른 경우만)
            command.severity?.let { code ->
                val c = errorCases.findById(created.id)!!
                c.update(c.title, c.project, c.snapshot, c.description, Meta.create(c.meta.workspaceId, code), c.visibility)
                errorCases.update(c)
            }
            val stepId = draft.workaroundStep?.takeIf { command.includeWorkaroundStep }?.let { w ->
                // 우회는 "부분적 진전" = IN_PROGRESS. 첫 Step 이라 Case 도 OPEN → IN_PROGRESS 로 바뀐다(기존 Step 규칙)
                createStep.invoke(CreateStepCommand(created.id, command.userId, w.title, StepStatus.IN_PROGRESS, null, w.body, null)).step.id
            }
            val now = Instant.now()
            val issue = recorder.saveIssue(resolved.item.snapshot)
            val outcome = recorder.attach(
                created.id, GithubRefKind.ISSUE, resolved.issueRef, LinkSource.IMPORTED, command.userId,
                stateEvents = recorder.issueStateEvents(created.id, issue), now = now,
            )
            Triple(created.id, outcome.link!!.id!!, stepId)
        }!!
        val (caseId, linkId, stepId) = result

        val errorCase = errorCases.findById(caseId)!!
        val backfilled = backfiller.backfill(errorCase, resolved.issueRef, resolved.item.pullRequestCandidates)
        log.info { "[github] case $caseId created from issue ${resolved.repository.fullName}#${resolved.issueRef.number} by=${command.userId} backfilled=$backfilled step=$stepId" }
        return Created(caseId, errorCase.title, errorCase.status.name, linkId, backfilled, stepId)
    }

    // ── 공통: 주소 → 레포·설치·범위·Issue ─────────────────────
    private data class Resolved(
        val repository: GithubRepository,
        val installation: GithubInstallation,
        val item: GithubAppApiPort.IssueItem,
        val issueRef: GithubItemRef,
        val workspaceId: Long?,
        val draft: GithubIssueCaseDraft,
        val visibilityOptions: List<Visibility>,
        val defaultVisibility: Visibility,
    )

    private fun resolve(userId: Long, rawUrl: String, requestedWorkspaceId: Long?): Resolved {
        val url = GithubUrl.parse(rawUrl)
            ?: throw GithubUrlInvalidException("지원하지 않는 GitHub 주소 (github.com 의 issues/pull 주소만 가능)")
        if (url.kind == GithubRefKind.PULL_REQUEST) throw GithubUrlInvalidException("PR 이 아니라 Issue 주소를 넣어 주세요")

        val (repository, installation) = scopeGuard.resolve(url.fullName)
        val workspaceId = installation.workspaceId
        if (requestedWorkspaceId != null && requestedWorkspaceId != workspaceId) {
            throw GithubScopeMismatchException("이 저장소는 ${if (workspaceId == null) "개인" else "다른 워크스페이스"} 범위로 연결되어 있어요")
        }
        val role: WorkspaceRole? = if (workspaceId != null) {
            workspaceQuery.getViewerRole(userId, workspaceId)?.takeIf { it.canWriteContent() }
                ?: throw WorkspaceAccessDeniedException("WRITE role required to create an error case in workspace $workspaceId")
        } else {
            if (installation.installedByUserId != userId) {
                throw GithubScopeMismatchException("개인으로 연결된 저장소는 연결한 본인만 Case 로 가져올 수 있어요")
            }
            null
        }

        val owner = repository.fullName.substringBefore('/')
        val name = repository.fullName.substringAfter('/')
        val item = when (val fetched = githubApi.fetchItem(installation.installationId, owner, name, url.number)) {
            null -> throw GithubItemNotFoundException("GitHub 에서 찾을 수 없음: ${repository.fullName}#${url.number}")
            is GithubAppApiPort.PullRequestItem -> throw GithubUrlInvalidException("PR 이 아니라 Issue 주소를 넣어 주세요 (#${url.number} 은 PR)")
            is GithubAppApiPort.IssueItem -> fetched
        }

        val options = buildList {
            if (workspaceId != null) add(Visibility.WORKSPACE)
            add(Visibility.PRIVATE)
            // 비공개 레포 Issue 는 공개 Case 금지. 워크스페이스 Case 의 PUBLIC 은 ADMIN 만(기존 생성 규칙과 동일).
            if (!repository.isPrivate && (workspaceId == null || role?.canAdminister() == true)) add(Visibility.PUBLIC)
        }
        return Resolved(
            repository, installation, item, GithubItemRef(item.repoId, item.snapshot.number), workspaceId,
            GithubIssueCaseDraft.from(item.snapshot, item.body, item.labels, repository.fullName),
            options, if (workspaceId != null) Visibility.WORKSPACE else Visibility.PRIVATE,
        )
    }

    /** 이 Issue 가 이미 붙은 Case 중 요청자가 볼 수 있는 것 — "이미 EA-87 에 연결되어 있어요" 안내용. */
    private fun alreadyLinkedCases(userId: Long, issueRef: GithubItemRef): List<LinkedCase> =
        evidence.findActiveLinkedCaseIds(GithubRefKind.ISSUE, issueRef).distinct().mapNotNull { id ->
            errorCases.findById(id)
                ?.takeIf { runCatching { access.requireRead(it, userId) }.isSuccess }
                ?.let { LinkedCase(id, it.title) }
        }
}
