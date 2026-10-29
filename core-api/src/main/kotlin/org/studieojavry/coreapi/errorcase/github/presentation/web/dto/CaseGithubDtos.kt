package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.github.application.usecase.GetCaseGithubEvidenceUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubActivityReader
import org.studieojavry.coreapi.errorcase.github.application.usecase.IssuePullRequestBackfiller
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
    @field:Schema(description = "Issue 를 붙였을 때 그 Issue 를 닫는 PR 소급 부착 결과. 응답 시점에 이미 끝나 있다. PR 연결이면 0/0")
    val backfill: GithubBackfillResponse,
) {
    companion object {
        fun of(link: CaseGithubLink, created: Boolean, backfill: IssuePullRequestBackfiller.Result) = CaseGithubLinkResponse(
            link.id!!, link.kind, link.repoId, link.number, link.linkSource, link.linkedAt, created, GithubBackfillResponse.of(backfill),
        )
    }
}

@Schema(description = "닫는 PR 소급 부착 결과 — 연결 요청 안에서 끝까지 돈 뒤 응답(비동기 아님)")
data class GithubBackfillResponse(
    @field:Schema(description = "함께 붙은 PR 수", example = "2") val attached: Int,
    @field:Schema(
        description = "GitHub 오류 등으로 확인하지 못한 후보 PR 수. 0 보다 크면 \"PR N건은 확인하지 못했어요\" 안내 — 나중에 그 PR 의 웹훅이 오면 붙을 수 있다",
        example = "0",
    )
    val failed: Int,
    @field:Schema(description = "함께 붙은 PR(연결 완료 안내에 표시). 길이 = attached") val pullRequests: List<GithubBackfilledPullRequestResponse>,
    @field:Schema(description = "이 Issue 를 닫지만 전에 \"관련 없음\"으로 뗐던 탓에 다시 붙이지 않은 PR") val skipped: List<GithubBackfillSkippedResponse>,
) {
    companion object {
        fun of(r: IssuePullRequestBackfiller.Result) = GithubBackfillResponse(
            r.attached,
            r.failed,
            r.pullRequests.map { GithubBackfilledPullRequestResponse(it.repo, it.number, it.title, it.state) },
            r.skipped.map { GithubBackfillSkippedResponse(it.repo, it.number) },
        )
    }
}

data class GithubBackfilledPullRequestResponse(
    @field:Schema(example = "acme/payment-api") val repo: String,
    @field:Schema(example = "61") val number: Int,
    @field:Schema(example = "fix: 주문 이벤트 멱등 키로 중복 생성 방지") val title: String,
    @field:Schema(description = "OPEN | CLOSED | MERGED", example = "MERGED") val state: String,
)

data class GithubBackfillSkippedResponse(
    @field:Schema(example = "acme/payment-api") val repo: String,
    @field:Schema(example = "62") val number: Int,
)

@Schema(
    description = "Case 의 GitHub 근거 화면 한 번에 — Issue ▸ 그 Issue 를 닫는 PR 관계형 근거(+CI) + GitHub 활동 첫 쪽. " +
        "근거와 활동은 같은 시점(한 DB 스냅샷)에서 읽는다. 비공개 레포 근거는 권한 없는 사람에게 가려져 내려간다.",
)
data class CaseGithubEvidenceResponse(
    @field:Schema(description = "연결된 레포 중 GitHub 연결이 끊긴(설치 삭제/정지·레포 제외) 것이 있음 — 새 소식이 더 이상 반영되지 않음")
    val syncStopped: Boolean,
    @field:Schema(description = "연결된 Issue(연결 순). 각 Issue 의 `pullRequests` 에 그 Issue 를 닫는 PR 이 들어 있다.")
    val issues: List<GithubEvidenceItemResponse>,
    @field:Schema(
        description = "어느 Issue 아래에도 묶이지 않은 PR(연결 순). **위치일 뿐 출처가 아님** — 직접 연결한 PR 일 수도, " +
            "Issue 로 자동 연결됐다가 그 Issue 를 뗀 PR 일 수도 있다(출처는 `linkSource`). 한 PR 은 한 곳에만 나온다.",
    )
    val ungroupedPullRequests: List<GithubEvidenceItemResponse>,
    @field:Schema(description = "가려진 항목 수(Issue 아래 PR 포함) — FE 표시용(예: \"비공개 저장소 PR 1건\")", example = "{\"ISSUE\":0,\"PULL_REQUEST\":1}")
    val maskedCount: Map<GithubRefKind, Int>,
    @field:Schema(description = "GitHub 활동 첫 쪽(최신 20건). 이전 활동은 `GET .../github/events?cursor={nextCursor}`")
    val activity: GithubActivityResponse,
) {
    companion object {
        fun of(v: GetCaseGithubEvidenceUseCase.EvidenceView) = CaseGithubEvidenceResponse(
            syncStopped = v.syncStopped,
            issues = v.issues.map { g -> GithubEvidenceItemResponse.of(g.issue, g.pullRequests.map(GithubEvidenceItemResponse::of)) },
            ungroupedPullRequests = v.ungroupedPullRequests.map(GithubEvidenceItemResponse::of),
            maskedCount = v.maskedCount,
            activity = GithubActivityResponse.of(v.activity),
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
    @field:Schema(description = "VIA_ISSUE 일 때 경유한 Issue. 그 Issue 가 요청자에게 가려지면 없음") val viaIssue: GetCaseGithubEvidenceUseCase.RefView?,
    @field:Schema(
        description = "PR 에만 있음 — 이 PR 이 지금 닫는 Issue(빈 배열 가능). `inCase=true` 면 그 Issue 도 이 Case 에 붙어 있음. " +
            "여러 Issue 를 닫아도 PR 은 한 Issue 아래에만 보이므로 \"다른 Issue 1건도 해결\" 표시에 쓴다. " +
            "경유 Issue(viaIssue)가 여기 없으면 그 Issue 를 더 이상 닫지 않음. 가려진 저장소의 Issue 는 빠진다.",
    )
    val closesIssues: List<GetCaseGithubEvidenceUseCase.ClosingIssueView>?,
    val diff: GetCaseGithubEvidenceUseCase.DiffView?,
    @field:Schema(description = "PR 최신 head 커밋의 CI 요약. stale=true 면 새 커밋 CI 대기 중") val ci: GetCaseGithubEvidenceUseCase.CiView?,
    @field:Schema(
        description = "이 근거가 꼬리표로 붙은 Step id(빈 배열 = Case 전체 근거). 가려진 항목에도 내려간다. " +
            "Solution 의 근거는 Solution 의 stepIds 와 이 값을 맞춰 FE 가 계산한다.",
        example = "[101, 103]",
    )
    val stepIds: List<Long>,
    @field:Schema(
        description = "Issue 에만 있음 — 이 Issue 아래에 보일 PR(연결 순, 빈 배열 가능): 이 Issue 를 경유해 붙은 PR, 또는 직접 붙였어도 이 Issue 를 닫는 PR. " +
            "Issue 가 가려졌으면 항상 빈 배열(그 PR 들은 `ungroupedPullRequests` 로). 가려진 PR 은 Issue 아래에 묶지 않는다.",
    )
    val pullRequests: List<GithubEvidenceItemResponse>? = null,
) {
    companion object {
        fun of(i: GetCaseGithubEvidenceUseCase.ItemView, children: List<GithubEvidenceItemResponse>? = null) = GithubEvidenceItemResponse(
            i.linkId, i.kind, i.masked, i.state, i.repo, i.number, i.isPrivate, i.title, i.url, i.author,
            i.linkSource, i.linkedAt, i.isDraft, i.viaIssue, i.closesIssues, i.diff, i.ci, i.stepIds, children,
        )
    }
}

@Schema(description = "GitHub 활동 한 쪽(최신순). 근거 조회의 `activity` 와 `GET .../github/events` 가 같은 모양·같은 규칙")
data class GithubActivityResponse(
    @field:Schema(description = "최신순(GitHub 발생 시각 내림차순, 같은 시각은 id 내림차순). FE 는 Step 과 시간순으로 섞고, 다시 받으면 id 로 합친다.")
    val events: List<GithubEvidenceEventResponse>,
    @field:Schema(description = "더 오래된 활동을 받을 커서. 없으면(null) 마지막 쪽", example = "MjAyNi0xMC0yMFQwMTowMDowMFp8NDI")
    val nextCursor: String?,
    val hasNext: Boolean,
    @field:Schema(description = "이 Case 의 GitHub 활동 전체 건수. 가려진 줄도 masked 로 내려가므로 넘겨볼 수 있는 줄 수와 같다", example = "124")
    val totalCount: Long,
) {
    companion object {
        fun of(p: GithubActivityReader.ActivityPage) = GithubActivityResponse(
            p.events.map(GithubEvidenceEventResponse::of), p.nextCursor, p.nextCursor != null, p.totalCount,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "근거 타임라인 한 줄")
data class GithubEvidenceEventResponse(
    @field:Schema(description = "이벤트 id — 목록 key 용") val id: Long,
    @field:Schema(description = "ISSUE_LINKED, ISSUE_CLOSED, ISSUE_REOPENED, PR_LINKED, PR_OPENED, PR_MERGED, PR_CLOSED, PR_REOPENED, CI_COMPLETED, REF_REMOVED, LINK_DETACHED")
    val type: String,
    @field:Schema(description = "GitHub 쪽 발생 시각") val occurredAt: Instant,
    val kind: GithubRefKind,
    val repo: String?,
    val number: Int?,
    val masked: Boolean,
    @field:Schema(description = "함께 가리키는 Issue — PR_LINKED(그 Issue 를 닫는 PR 이라 자동 연결)·REF_REMOVED(그 Issue 를 더 이상 닫지 않음). 가려지면 없음")
    val issue: GetCaseGithubEvidenceUseCase.RefView?,
    @field:Schema(description = "종류별 부가 정보. CI_COMPLETED: sha·conclusion·total·passed·failed (가려지면 conclusion 만)")
    val data: Map<String, Any?>,
) {
    companion object {
        fun of(e: GithubActivityReader.EventView) = GithubEvidenceEventResponse(
            e.id, e.type.name, e.occurredAt, e.kind, e.repo, e.number, e.masked, e.issue, e.data,
        )
    }
}
