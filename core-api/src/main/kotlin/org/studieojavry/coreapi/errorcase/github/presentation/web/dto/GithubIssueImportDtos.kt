package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.github.application.usecase.ImportGithubIssueUseCase
import java.time.Instant

@Schema(description = "GitHub Issue → Case 초안 미리보기 (저장하지 않음)")
data class GithubIssuePreviewResponse(
    @field:Schema(description = "제안 제목(Issue 제목, 200자 컷)") val title: String,
    @field:Schema(description = "제안 본문 — Issue 본문에서 Error Trace 로 옮긴 블록은 안내 한 줄로, 체크리스트·빈 응답 섹션은 빼고, 끝에 출처 한 줄")
    val description: String,
    @field:Schema(description = "Error Trace — 에러 로그로 보이는 코드 블록만(예외 클래스·스택 프레임·Traceback·panic, 로그 섹션의 에러). 없으면 null")
    val paste: String?,
    @field:Schema(description = "paste 에서 추출한 예외 미리보기") val snapshotPreview: SnapshotPreview,
    @field:Schema(description = "본문의 코드 블록 전부. errorLog=true 인 것만 paste 에 들어감 — FE 에서 켜고 끄며 paste 를 다시 만들 수 있게")
    val codeBlocks: List<CodeBlock>,
    @field:Schema(description = "기본 태그 — 항상 빈 목록(라벨을 자동으로 넣지 않음)") val tags: List<String>,
    @field:Schema(description = "라벨에서 고른 태그 후보 — 상태·종류·우선순위 라벨 제외, `in: web` 같은 주제 라벨은 값만. 기본 선택 해제로 보여 주기")
    val tagSuggestions: List<String>,
    @field:Schema(description = "우선순위/심각도 라벨에서 고른 심각도 후보. 없으면 null — 자동 반영하지 않음")
    val severitySuggestion: SeveritySuggestion?,
    @field:Schema(description = "우회 방법(Workaround) 섹션이 있으면 첫 Step 후보(진행 중). 생성 시 includeWorkaroundStep=true 로 만든다")
    val workaroundStep: StepSuggestion?,
    @field:Schema(description = "Issue 작성 시각(= 보고 시각). 발생 시각으로 자동 반영하지 않음") val reportedAt: Instant,
    @field:Schema(description = "Case 범위 — 저장소 연결 범위로 결정됨. null = 개인 Case") val workspaceId: Long?,
    @field:Schema(description = "고를 수 있는 공개 범위. 비공개 저장소면 PUBLIC 없음, 워크스페이스 PUBLIC 은 ADMIN 만")
    val visibilityOptions: List<Visibility>,
    val defaultVisibility: Visibility,
    val repo: RepoInfo,
    val issue: IssueInfo,
    @field:Schema(description = "이 Issue 가 이미 붙은 Case(내가 볼 수 있는 것) — \"이미 연결되어 있어요\" 안내용")
    val alreadyLinkedCases: List<LinkedCase>,
) {
    data class SnapshotPreview(val exceptionClass: String?, val exceptionMessage: String?)
    data class CodeBlock(
        @field:Schema(description = "블록이 있던 섹션 제목(없으면 null)", example = "Logs") val section: String?,
        @field:Schema(example = "text") val language: String?,
        val text: String,
        @field:Schema(description = "에러 로그로 판단해 paste 에 넣었는지") val errorLog: Boolean,
    )
    data class SeveritySuggestion(
        @field:Schema(description = "1=Outage 2=Degraded 3=Minor 4=Info", example = "2") val code: Int,
        @field:Schema(description = "근거 라벨", example = "priority: high") val fromLabel: String,
    )
    data class StepSuggestion(
        @field:Schema(example = "우회 방법 (GitHub Issue #142)") val title: String,
        @field:Schema(description = "Workaround 섹션 원문") val body: String,
        @field:Schema(description = "항상 IN_PROGRESS (우회 = 부분적 진전)") val status: String = "IN_PROGRESS",
    )
    data class RepoInfo(@field:Schema(example = "acme/payment-api") val fullName: String, val isPrivate: Boolean)
    data class IssueInfo(val number: Int, val url: String, @field:Schema(description = "OPEN | CLOSED") val state: String)
    data class LinkedCase(val id: Long, val title: String)

    companion object {
        fun of(p: ImportGithubIssueUseCase.Preview) = GithubIssuePreviewResponse(
            title = p.draft.title,
            description = p.draft.description,
            paste = p.draft.paste,
            snapshotPreview = SnapshotPreview(p.exceptionClass, p.exceptionMessage),
            codeBlocks = p.draft.codeBlocks.map { CodeBlock(it.section, it.language, it.text, it.errorLog) },
            tags = p.draft.tags,
            tagSuggestions = p.draft.tagSuggestions,
            severitySuggestion = p.draft.severitySuggestion?.let { SeveritySuggestion(it.code, it.fromLabel) },
            workaroundStep = p.draft.workaroundStep?.let { StepSuggestion(it.title, it.body) },
            reportedAt = p.draft.reportedAt,
            workspaceId = p.workspaceId,
            visibilityOptions = p.visibilityOptions,
            defaultVisibility = p.defaultVisibility,
            repo = RepoInfo(p.repoFullName, p.repoIsPrivate),
            issue = IssueInfo(p.issueNumber, p.issueUrl, p.issueState),
            alreadyLinkedCases = p.alreadyLinkedCases.map { LinkedCase(it.id, it.title) },
        )
    }
}

@Schema(description = "GitHub Issue 로 Case 생성. url 외 필드는 미리보기에서 고친 값 — 생략하면 미리보기 기본값")
data class CreateCaseFromGithubIssueRequest(
    @field:Schema(example = "https://github.com/acme/payment-api/issues/142", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank @field:Size(max = 500)
    val url: String,
    @field:Schema(description = "확인용. 저장소 연결 범위와 다르면 403") val workspaceId: Long? = null,
    @field:Schema(description = "생략 시 워크스페이스=WORKSPACE, 개인=PRIVATE") val visibility: Visibility? = null,
    @field:Size(max = 200) val title: String? = null,
    val description: String? = null,
    @field:Schema(description = "Error Trace. 생략 시 미리보기의 paste(에러 로그로 보이는 블록만)") val paste: String? = null,
    @field:Schema(description = "생략 시 빈 목록 — 라벨은 자동으로 넣지 않음(tagSuggestions 에서 사용자가 고른 것을 보내기)")
    val tags: List<String>? = null,
    @field:Schema(description = "심각도 1(Outage)~4(Info). 생략 시 비움", example = "2")
    @field:Min(1) @field:Max(4)
    val severity: Int? = null,
    @field:Schema(description = "발생 시각. 생략 시 비움(Issue 작성 시각을 발생 시각으로 쓰지 않음)")
    val occurredAt: Instant? = null,
    @field:Schema(description = "true 면 Workaround 섹션으로 첫 Step(IN_PROGRESS) 생성 → Case 는 IN_PROGRESS. 섹션 없는 Issue 면 400")
    val includeWorkaroundStep: Boolean = false,
)

@Schema(description = "Issue 로 만든 Case")
data class CreateCaseFromGithubIssueResponse(
    val caseId: Long,
    val title: String,
    @field:Schema(description = "OPEN. 우회 Step 을 함께 만들면 IN_PROGRESS") val status: String,
    @field:Schema(description = "Issue 연결 id (출처 IMPORTED)") val linkId: Long,
    @field:Schema(description = "그 Issue 를 닫는 PR 중 함께 붙은 수(소급 부착)") val backfilledPullRequests: Int,
    @field:Schema(description = "우회 방법으로 만든 Step id. 안 만들었으면 null") val workaroundStepId: Long?,
) {
    companion object {
        fun of(c: ImportGithubIssueUseCase.Created) =
            CreateCaseFromGithubIssueResponse(c.caseId, c.title, c.status, c.linkId, c.backfilledPullRequests, c.workaroundStepId)
    }
}
