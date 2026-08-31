package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * 멘션 자동완성 후보 v2.
 *
 * **후보 풀** (가중치 합산):
 *  - ① 케이스 댓글 참여자  ⭐⭐⭐⭐⭐ (30)
 *  - ② 케이스 owner       ⭐⭐⭐⭐  (20)
 *  - ③ 워크스페이스 멤버  ⭐⭐⭐⭐  (15) — 워크스페이스 케이스만
 *  - ④ iam-api prefix 검색 ⭐⭐    (5)  — q 가 비어있지 않을 때 fallback 풀
 *
 * **권한 가드**:
 *  - viewer 가 케이스 read 가능 (이미 `ErrorCaseAccess.requireRead` 로 검증)
 *  - viewer 본인 / DELETED 사용자는 제외 (iam-api 가 ACTIVE 만 반환)
 *  - 워크스페이스 케이스에선 *멤버 외* 후보 제외 — 외부 사용자 멘션 시 정보 누출 방지
 *
 * **랭킹**: 신호별 점수 합 + prefix 일치 보너스 + alphabetical tie-break.
 */
@Service
class MentionCandidatesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val iamUserQuery: IamUserQueryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, viewerUserId: Long, rawQuery: String?, limit: Int): List<Item> {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, viewerUserId)

        val q = rawQuery?.trim().orEmpty()
        val cap = limit.coerceIn(1, 20)
        val isWorkspaceCase = errorCase.meta.workspaceId != null

        // 신호별 후보 + 점수 적재 (userId → score 누적)
        val scored = HashMap<Long, Score>()

        // ① 케이스 댓글 참여자
        val discussionAuthors = commentRepository.findAllByErrorCaseId(errorCaseId)
            .map { it.authorUserId }
            .distinct()
        discussionAuthors.forEach { uid -> scored.bump(uid, 30, BadgeKind.IN_DISCUSSION) }

        // ② 케이스 owner
        scored.bump(errorCase.ownerUserId, 20, BadgeKind.CASE_OWNER)

        // ③ 워크스페이스 멤버 (워크스페이스 케이스만)
        val workspaceMembers: List<WorkspaceQueryPort.MemberSummary> =
            if (isWorkspaceCase) workspaceQuery.listMembers(errorCase.meta.workspaceId!!) else emptyList()
        workspaceMembers.forEach { m -> scored.bump(m.userId, 15, BadgeKind.WORKSPACE) }

        // 표시 정보 캐시 — iam-api 한 번에 모은다
        val needIds = scored.keys.toMutableSet()
        // ④ iam-api prefix 검색 (q 가 비어있지 않을 때만)
        val searchHits: List<IamUserQueryPort.UserSummary> =
            if (q.isNotEmpty()) iamUserQuery.searchByDisplayNamePrefix(q, cap * 2) else emptyList()
        searchHits.forEach { u ->
            scored.bump(u.userId, 5, BadgeKind.SEARCH)
            needIds += u.userId
        }

        // 본인 제거
        needIds -= viewerUserId
        scored.remove(viewerUserId)

        // 표시 정보 fetch. searchHits 는 handle 포함 응답이라 그대로 쓰고,
        // 나머지(댓글 참여자·owner·워크스페이스 멤버)는 findByIds(공개 프로필)로 채운다.
        // — 워크스페이스 멤버도 여기서 조회해야 handle 을 얻는다(MemberSummary 엔 handle 없음).
        val infoById = HashMap<Long, IamUserQueryPort.UserSummary>()
        searchHits.forEach { infoById[it.userId] = it }
        val missing = needIds - infoById.keys
        if (missing.isNotEmpty()) {
            iamUserQuery.findByIds(missing).forEach { infoById[it.userId] = it }
        }

        // 워크스페이스 케이스 → 멤버 외 후보 제거 (security gate)
        val finalIds: List<Long> = if (isWorkspaceCase) {
            val memberIds = workspaceMembers.mapTo(HashSet()) { it.userId }
            scored.keys.filter { it in memberIds }
        } else {
            scored.keys.toList()
        }

        // 랭킹: 점수 desc + prefix 일치 보너스 + name asc
        val results = finalIds
            .mapNotNull { uid ->
                val info = infoById[uid] ?: return@mapNotNull null
                val s = scored[uid] ?: return@mapNotNull null
                var rank = s.score
                if (q.isNotEmpty() && info.displayName.startsWith(q, ignoreCase = true)) rank += 12
                Triple(info, s.topBadge, rank)
            }
            .sortedWith(
                compareByDescending<Triple<IamUserQueryPort.UserSummary, BadgeKind?, Int>> { it.third }
                    .thenBy { it.first.displayName.lowercase() }
            )
            .take(cap)
            .map { (u, badge, _) ->
                Item(userId = u.userId, displayName = u.displayName, handle = u.handle, avatarUrl = u.avatarUrl, badge = badge?.code)
            }

        return results
    }

    private fun HashMap<Long, Score>.bump(userId: Long, weight: Int, badge: BadgeKind) {
        val cur = this[userId]
        if (cur == null) this[userId] = Score(weight, badge)
        else this[userId] = Score(cur.score + weight, betterBadge(cur.topBadge, badge))
    }
    private fun betterBadge(a: BadgeKind, b: BadgeKind): BadgeKind =
        if (a.priority <= b.priority) a else b

    data class Item(val userId: Long, val displayName: String, val handle: String?, val avatarUrl: String?, val badge: String?)
    private data class Score(val score: Int, val topBadge: BadgeKind)

    private enum class BadgeKind(val priority: Int, val code: String) {
        IN_DISCUSSION(1, "in-discussion"),
        CASE_OWNER(2,    "owner"),
        WORKSPACE(3,     "workspace-member"),
        SEARCH(4,        "search"),
    }
}