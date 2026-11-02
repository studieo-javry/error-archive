package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubEventCursor
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * Case 의 GitHub 활동(근거 타임라인) 한 쪽 — **최신순 (occurredAt↓, id↓) 커서**. 이벤트는 모두 보존하고 보는 구간만 읽는다.
 * 근거 조회의 첫 쪽([GetCaseGithubEvidenceUseCase])과 "더 보기"([ListCaseGithubEventsUseCase])가 **이 로직 하나**를 같이 쓴다 —
 * 정렬·가림·커서 규칙이 둘 사이에서 갈라지지 않게. 열람 권한 확인은 호출하는 쪽 몫.
 *
 * 정렬은 GitHub 쪽 발생 시각 — 연결 시점에 과거 사건(예: 3일 전 PR 열림)을 기록하므로 저장 시각으로는 연대기가 틀어진다.
 * 커서는 **데이터가 바뀌지 않는 동안** 겹침·빠짐 없이 나눠 준다. 넘기는 도중 바뀌면 완전성은 보장하지 않는다(현 API 의 한계):
 *  - 이미 지나간 구간에 늦게 기록된 과거 이벤트(예: Issue 연결 때 소급 저장된 PR 열림)는 이어 받는 커서로는 안 나오고,
 *    첫 쪽 재조회에서도 20건 밖이면 안 보인다.
 *  - CI 집계 upsert 가 occurredAt 을 옮기면 같은 id 가 다시 나오거나(FE 는 id 로 합침) 아직 안 받은 구간에서 빠질 수 있다.
 *  - 이미 받은 줄의 내용·시각이 바뀐 것(CI upsert — id·건수 그대로)은 건수로는 알 수 없다. 첫 쪽 재조회 범위 안이면 id 로 합치며 갱신된다.
 *  빠진 줄의 단서는 **끝까지 받은 뒤** 고유 id 수 < totalCount 일 때뿐(넘기는 도중엔 모름) — 그때 FE 가 처음부터 다시 받아야 한다.
 *  바뀐 줄까지 알아내려면 Case 별 활동 리비전이 필요(후속, P1-d 와 함께). 동작은 GithubActivityCursorIntegrationTest 에 기록.
 */
@Component
@ConditionalOnGithubEnabled
class GithubActivityReader(
    private val policy: GithubCaseAccessPolicy,
    private val evidence: GithubEvidenceRepositoryPort,
    private val installations: GithubInstallationRepositoryPort,
) {
    fun page(errorCase: ErrorCase, viewerUserId: Long, cursor: String?, size: Int): ActivityPage {
        val caseId = errorCase.id!!
        val before = cursor?.let { GithubEventCursor.decode(it) ?: throw GithubEventCursorInvalidException("잘못된 커서: $it") }
        val limit = size.coerceIn(1, MAX_SIZE)

        // 한 건 더 읽어 다음 쪽이 있는지 판단
        val fetched = evidence.findEventsBefore(caseId, before, limit + 1)
        val page = fetched.take(limit)
        val hasNext = fetched.size > limit

        val repoIds = (page.map { it.repoId } + page.mapNotNull { it.relatedIssueRepoId() }).toSet()
        val repos = installations.findRepositoriesByIds(repoIds).associateBy { it.repoId }
        val canSeePrivate by lazy { policy.canSeePrivate(errorCase, viewerUserId) }
        fun visible(repoId: Long): Boolean = policy.isVisible(repos[repoId], canSeePrivate)

        return ActivityPage(
            events = page.map { e ->
                if (visible(e.repoId)) {
                    val issue = e.relatedIssueRepoId()?.takeIf(::visible)
                        ?.let { r -> GetCaseGithubEvidenceUseCase.RefView(repos[r]?.fullName, (e.data[ISSUE_NUMBER] as Number).toInt()) }
                    EventView(e.id!!, e.type, e.occurredAt, e.kind, repos[e.repoId]?.fullName, e.number, masked = false,
                        issue = issue, data = e.data - RELATED_ISSUE_KEYS)
                } else {
                    // 외부인에게는 "언제 무슨 일이 있었나"만. CI 는 결과(통과/실패)까지만, 실패 job 이름·sha 는 제외.
                    val data = if (e.type == CaseGithubEvent.Type.CI_COMPLETED) e.data.filterKeys { it == "conclusion" } else emptyMap()
                    EventView(e.id!!, e.type, e.occurredAt, e.kind, null, null, masked = true, issue = null, data = data)
                }
            },
            nextCursor = if (hasNext) page.last().let { GithubEventCursor(it.occurredAt, it.id!!).encode() } else null,
            // 가려진 줄도 지우지 않고 masked 로 내려가므로 전체 건수 = 이 사람이 넘겨볼 수 있는 줄 수
            totalCount = evidence.countEvents(caseId),
        )
    }

    /** 이벤트가 가리키는 다른 Issue(예: "PR 이 Issue #N 을 닫는 PR 이라 붙음"). 레포 id 그대로는 내보내지 않는다. */
    private fun CaseGithubEvent.relatedIssueRepoId(): Long? =
        (data[ISSUE_REPO_ID] as? Number)?.toLong()?.takeIf { data[ISSUE_NUMBER] is Number }

    data class ActivityPage(
        /** 최신순. */
        val events: List<EventView>,
        /** 더 오래된 쪽을 받을 커서. 없으면 끝. */
        val nextCursor: String?,
        val totalCount: Long,
    )

    data class EventView(
        val id: Long,
        val type: CaseGithubEvent.Type,
        val occurredAt: Instant,
        val kind: GithubRefKind,
        val repo: String?,
        val number: Int?,
        val masked: Boolean,
        /** 이 이벤트가 함께 가리키는 Issue. 그 Issue 가 가려지면 없음. */
        val issue: GetCaseGithubEvidenceUseCase.RefView?,
        val data: Map<String, Any?>,
    )

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        /** 이벤트 data 에서 "관련 Issue" 를 가리키는 키 — 기록 쪽(P1-b 경유 연결, P1-c 참조 해제)이 이 이름으로 넣는다. */
        const val ISSUE_REPO_ID = "issueRepoId"
        const val ISSUE_NUMBER = "issueNumber"
        private val RELATED_ISSUE_KEYS = setOf(ISSUE_REPO_ID, ISSUE_NUMBER)
    }
}
