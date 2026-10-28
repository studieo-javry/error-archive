package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetCaseGithubEvidenceUseCase
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

@Schema(description = "Case 에 GitHub Issue/PR 연결 요청")
data class LinkGithubRequest(
    @field:Schema(
        description = "GitHub Issue 또는 PR 주소. `/issues/N` 이 실제로 PR 이면 PR 로 연결된다.",
        example = "https://github.com/acme/payment-api/pull/153",
    )
    @field:NotBlank
    @field:Size(max = 500)
    val url: String,
)

@Schema(description = "Step 의 GitHub 근거 꼬리표를 이 목록으로 맞춤(빈 목록 = 모두 해제)")
data class SetStepGithubLinksRequest(
    @field:Schema(description = "이 Case 에 붙어 있는 근거의 연결 id(`GET .../github` 의 linkId) 목록", example = "[12, 15]")
    @field:NotNull
    @field:Size(max = 50)
    val linkIds: List<Long>,
)

@Schema(description = "Step 의 GitHub 근거 꼬리표")
data class StepGithubLinksResponse(
    val stepId: Long,
    @field:Schema(description = "꼬리표로 붙은 연결 id") val linkIds: List<Long>,
)

@Schema(description = "연결 결과")
data class CaseGithubLinkResponse(
    @field:Schema(description = "연결 id (떼기에 사용)") val linkId: Long,
    @field:Schema(description = "ISSUE | PULL_REQUEST") val kind: GithubRefKind,
    @field:Schema(description = "GitHub repo id (불변)") val repoId: Long,
    @field:Schema(description = "Issue/PR 번호") val number: Int,
    @field:Schema(description = "MANUAL(직접 연결) | IMPORTED(Issue 로 Case 생성) | VIA_ISSUE(연결된 Issue 를 닫는 PR 자동 부착)")
    val linkSource: CaseGithubLink.LinkSource,
    val linkedAt: Instant,
    @field:Schema(description = "이번 요청으로 새로 붙었나(false = 이미 연결돼 있었음)") val created: Boolean,
) {
    companion object {
        fun of(link: CaseGithubLink, created: Boolean) = CaseGithubLinkResponse(
            link.id!!, link.kind, link.repoId, link.number, link.linkSource, link.linkedAt, created,
        )
    }
}

@Schema(description = "Case 의 GitHub 근거 — 연결된 Issue·PR(+CI)과 근거 타임라인. 비공개 레포 근거는 권한 없는 사람에게 가려져 내려간다.")
data class CaseGithubEvidenceResponse(
    @field:Schema(description = "연결된 레포 중 GitHub 연결이 끊긴(설치 삭제/정지·레포 제외) 것이 있음 — 새 소식이 더 이상 반영되지 않음")
    val syncStopped: Boolean,
    val issues: List<GithubEvidenceItemResponse>,
    val pullRequests: List<GithubEvidenceItemResponse>,
    @field:Schema(description = "근거 타임라인(occurredAt 오름차순). Step 목록과는 FE 가 시간순으로 섞어 보여준다.")
    val events: List<GithubEvidenceEventResponse>,
    @field:Schema(description = "가려진 항목 수 — FE 표시용(예: \"비공개 저장소 PR 1건\")", example = "{\"ISSUE\":0,\"PULL_REQUEST\":1}")
    val maskedCount: Map<GithubRefKind, Int>,
) {
    companion object {
        fun of(v: GetCaseGithubEvidenceUseCase.EvidenceView) = CaseGithubEvidenceResponse(
            syncStopped = v.syncStopped,
            issues = v.items.filter { it.kind == GithubRefKind.ISSUE }.map(GithubEvidenceItemResponse::of),
            pullRequests = v.items.filter { it.kind == GithubRefKind.PULL_REQUEST }.map(GithubEvidenceItemResponse::of),
            events = v.events.map(GithubEvidenceEventResponse::of),
            maskedCount = v.maskedCount,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "연결된 Issue/PR 하나. masked=true 면 kind·state 외 필드는 응답에 없다.")
data class GithubEvidenceItemResponse(
    val linkId: Long?,
    val kind: GithubRefKind,
    @field:Schema(description = "비공개 레포 근거라 상세를 가림") val masked: Boolean,
    @field:Schema(description = "Issue: OPEN|CLOSED, PR: OPEN|CLOSED|MERGED") val state: String,
    @field:Schema(example = "acme/payment-api") val repo: String?,
    val number: Int?,
    val isPrivate: Boolean?,
    val title: String?,
    val url: String?,
    val author: String?,
    val linkSource: CaseGithubLink.LinkSource?,
    val linkedAt: Instant?,
    val isDraft: Boolean?,
    @field:Schema(description = "VIA_ISSUE 일 때 경유한 Issue") val viaIssue: GetCaseGithubEvidenceUseCase.RefView?,
    val diff: GetCaseGithubEvidenceUseCase.DiffView?,
    @field:Schema(description = "PR 최신 head 커밋의 CI 요약. stale=true 면 새 커밋 CI 대기 중") val ci: GetCaseGithubEvidenceUseCase.CiView?,
    @field:Schema(
        description = "이 근거가 꼬리표로 붙은 Step id(빈 배열 = Case 전체 근거). 가려진 항목에도 내려간다. " +
            "Solution 의 근거는 Solution 의 stepIds 와 이 값을 맞춰 FE 가 계산한다.",
        example = "[101, 103]",
    )
    val stepIds: List<Long>,
) {
    companion object {
        fun of(i: GetCaseGithubEvidenceUseCase.ItemView) = GithubEvidenceItemResponse(
            i.linkId, i.kind, i.masked, i.state, i.repo, i.number, i.isPrivate, i.title, i.url, i.author,
            i.linkSource, i.linkedAt, i.isDraft, i.viaIssue, i.diff, i.ci, i.stepIds,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "근거 타임라인 한 줄")
data class GithubEvidenceEventResponse(
    @field:Schema(description = "ISSUE_LINKED, ISSUE_CLOSED, PR_LINKED, PR_OPENED, PR_MERGED, PR_CLOSED, CI_COMPLETED, LINK_DETACHED …")
    val type: String,
    @field:Schema(description = "GitHub 쪽 발생 시각") val occurredAt: Instant,
    val kind: GithubRefKind,
    val repo: String?,
    val number: Int?,
    val masked: Boolean,
    @field:Schema(description = "종류별 부가 정보. CI_COMPLETED: sha·conclusion·total·passed·failed (가려지면 conclusion 만)")
    val data: Map<String, Any?>,
) {
    companion object {
        fun of(e: GetCaseGithubEvidenceUseCase.EventView) = GithubEvidenceEventResponse(
            e.type.name, e.occurredAt, e.kind, e.repo, e.number, e.masked, e.data,
        )
    }
}
