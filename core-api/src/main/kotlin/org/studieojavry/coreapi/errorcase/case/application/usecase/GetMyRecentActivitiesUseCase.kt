package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.shared.config.CacheConfig
import java.time.LocalDateTime

/**
 * my-page / author-profile 의 "Recent activities" — target 사용자가 최근 N일 안에 한 활동 timeline.
 *
 * 5 종류 activity union:
 *  - CASE_CREATED — target 이 만든 케이스
 *  - CASE_RESOLVED — target 이 RESOLVED 로 전환한 케이스
 *  - COMMENT_POSTED — target 이 작성한 댓글 (deleted 제외)
 *  - STEP_ADDED — target 이 추가한 step
 *  - SOLUTION_REGISTERED — target 이 등록한 solution
 *
 * **Viewer-aware visibility filter** (author-profile 노출 필수):
 *  - `viewerUserId == userId` (self view) → 필터 X (자기 activity 전부)
 *  - `viewerUserId != userId` (other view):
 *    - PUBLIC case activity → 통과
 *    - WORKSPACE case activity → viewer 가 그 workspace 멤버여야 통과
 *    - PRIVATE case activity → 제외
 *
 * **Summary counts** 도 관점별 다름:
 *  - Self view: `countByAuthorSince` 등 window 전체 count query 사용 (기존 동작)
 *  - Viewer view: post-filter items 기반 count — window 전체가 아닌 "viewer 가 볼 수 있는 top-N" 근사
 *
 * 정렬: occurredAt DESC. limit: 30. Case title 은 일괄 fetch 로 보충.
 */
@Service
class GetMyRecentActivitiesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    /**
     * Cache key = `target:viewer` — viewer 별로 결과가 다르므로 분리.
     * Self view (target == viewer) 는 여전히 같은 key 로 hit 공유. Other view 는 viewer 별 별도 캐시.
     */
    @Cacheable(cacheNames = [CacheConfig.CACHE_MY_RECENT_ACTIVITIES], key = "#input.userId + ':' + #input.viewerUserId")
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)
        val sinceInstant = LocalDateTime.now().minusDays(input.windowDays.toLong())
        val perSourceLimit = limit + 5
        val isSelfView = input.viewerUserId == input.userId

        // 1. Fetch activities from 4 sources
        val activities = buildList {
            commentRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(
                    type = ActivityType.COMMENT_POSTED,
                    caseId = it.errorCaseId,
                    occurredAt = it.createdAt,
                    commentPreview = previewOf(it.body),
                    commentIsReply = it.parentCommentId != null,
                ))
            }
            stepRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(
                    type = ActivityType.STEP_ADDED,
                    caseId = it.errorCaseId,
                    occurredAt = it.createdAt,
                    stepTitle = it.title,
                    stepAttemptType = it.attemptType,
                ))
            }
            solutionRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(
                    type = ActivityType.SOLUTION_REGISTERED,
                    caseId = it.errorCaseId,
                    occurredAt = it.createdAt,
                    solutionTitle = it.title,
                    solutionStepCount = it.stepIds.size,
                ))
            }
            errorCaseRepository.findRecentByOwner(input.userId, perSourceLimit)
                .filter { !it.createdAt.isBefore(sinceInstant) }
                .forEach {
                    add(Item(type = ActivityType.CASE_CREATED, caseId = it.id, occurredAt = it.createdAt))
                }
            val resolvedCases = errorCaseRepository.findRecentlyResolvedByActor(input.userId, sinceInstant, perSourceLimit)
            val resolvedStepCounts: Map<Long, Long> = if (resolvedCases.isEmpty()) emptyMap()
                else stepRepository.countByCaseIds(resolvedCases.map { it.id }.toSet())
            resolvedCases.forEach {
                val at = it.resolvedAt ?: return@forEach
                add(Item(
                    type = ActivityType.CASE_RESOLVED,
                    caseId = it.id,
                    occurredAt = at,
                    resolvedAfterSteps = resolvedStepCounts[it.id]?.toInt() ?: 0,
                ))
            }
        }

        // 2. Batch fetch case metadata for all activity caseIds (visibility / workspaceId / title)
        val caseIds = activities.map { it.caseId }.toSet()
        val casesById: Map<Long, ErrorCaseSummary> = if (caseIds.isEmpty()) emptyMap()
            else errorCaseRepository.findSummariesByIds(caseIds).associateBy { it.id }

        // 3. Viewer 의 workspace 매핑 fetch — filter (viewer view) + workspace name lookup (both views) 겸용.
        //    getAvailableWorkspaces(viewerUserId) 는 iam-api 1회 호출. 실패 시 빈 리스트로 degrade.
        val viewerWorkspaces = runCatching {
            workspaceQuery.getAvailableWorkspaces(input.viewerUserId)
        }.getOrDefault(emptyList())
        val viewerWorkspaceIds: Set<Long> = viewerWorkspaces.map { it.workpaceId }.toSet()
        val workspaceNameById: Map<Long, String> = viewerWorkspaces.associateBy({ it.workpaceId }, { it.name })

        // 4. Visibility filter — viewer != target 일 때만 적용
        val visible = if (isSelfView) activities
            else activities.filter { activity -> isVisibleToViewer(casesById[activity.caseId], viewerWorkspaceIds) }

        // 5. Sort + take(limit) + case-level metadata hydrate
        val top = visible.sortedByDescending { it.occurredAt }.take(limit)
        val hydrated = top.map { item ->
            val case = casesById[item.caseId]
            item.copy(
                caseTitle = case?.title,
                caseVisibility = case?.visibility?.name,
                caseWorkspaceId = case?.workspaceId,
                caseWorkspaceName = case?.workspaceId?.let { workspaceNameById[it] },
            )
        }

        // 5. Summary — 관점별 계산 분리
        val summary = if (isSelfView) {
            // Self view: window 전체 count query (정확한 총합)
            ActivitySummary(
                byType = mapOf(
                    ActivityType.CASE_CREATED to errorCaseRepository.countCreatedByOwnerSince(input.userId, sinceInstant),
                    ActivityType.CASE_RESOLVED to errorCaseRepository.countResolvedByActorSince(input.userId, sinceInstant),
                    ActivityType.COMMENT_POSTED to commentRepository.countByAuthorSince(input.userId, sinceInstant),
                    ActivityType.STEP_ADDED to stepRepository.countByAuthorSince(input.userId, sinceInstant),
                    ActivityType.SOLUTION_REGISTERED to solutionRepository.countByAuthorSince(input.userId, sinceInstant),
                )
            )
        } else {
            // Viewer view: post-filter 활동 카운트. window 전체가 아니라 "볼 수 있는 최근" 근사.
            // 정보 유출 방지 관점에서 count query 를 별도로 돌리지 않음.
            ActivitySummary(
                byType = ActivityType.entries.associateWith { type ->
                    visible.count { it.type == type }.toLong()
                }
            )
        }

        return Result(items = hydrated, windowDays = input.windowDays, summary = summary)
    }

    /**
     * viewer 관점의 visibility 필터.
     * - case 가 조회되지 않으면 (삭제 등) 제외
     * - PUBLIC → 통과
     * - WORKSPACE → viewer 가 그 workspace 멤버여야 통과
     * - PRIVATE → 제외
     */
    private fun isVisibleToViewer(case: ErrorCaseSummary?, viewerWorkspaceIds: Set<Long>): Boolean {
        if (case == null) return false
        return when (case.visibility) {
            Visibility.PUBLIC -> true
            Visibility.WORKSPACE -> case.workspaceId != null && case.workspaceId in viewerWorkspaceIds
            Visibility.PRIVATE -> false
        }
    }

    /** windowDays 전체의 type 별 합계 — pills 표시용. items 의 limit 와 무관 (self view 만 정확). */
    data class ActivitySummary(val byType: Map<ActivityType, Long>)

    enum class ActivityType { CASE_CREATED, CASE_RESOLVED, COMMENT_POSTED, STEP_ADDED, SOLUTION_REGISTERED }

    data class Item(
        val type: ActivityType,
        val caseId: Long,
        val caseTitle: String? = null,
        val occurredAt: LocalDateTime,
        /** COMMENT_POSTED — body 앞 60자 (공백 정규화 + trim). */
        val commentPreview: String? = null,
        /** COMMENT_POSTED — parentCommentId != null 인 경우 reply. */
        val commentIsReply: Boolean = false,
        /** STEP_ADDED — step.title. */
        val stepTitle: String? = null,
        /** STEP_ADDED — step.attemptType (system 상수 or 사용자 custom). null 가능. */
        val stepAttemptType: String? = null,
        /** SOLUTION_REGISTERED — solution.title. */
        val solutionTitle: String? = null,
        /** SOLUTION_REGISTERED — solution 이 참조하는 step 개수. */
        val solutionStepCount: Int? = null,
        /** CASE_RESOLVED — case 가 resolve 시점에 가지고 있던 step 총수. "after N steps" 표시용. */
        val resolvedAfterSteps: Int? = null,
        /** Case-level 속성 — badge 렌더용. All types. PUBLIC / WORKSPACE / PRIVATE. */
        val caseVisibility: String? = null,
        /** Case-level workspaceId — case 가 워크스페이스 소속이면 채워짐. */
        val caseWorkspaceId: Long? = null,
        /**
         * Case-level workspace 이름 — badge 표시용. viewer 가 그 workspace 멤버일 때만 resolve 가능.
         * 예외: PUBLIC 이지만 workspace 에 속한 (ADMIN 이 승격한) case → viewer 가 non-member 면 null.
         */
        val caseWorkspaceName: String? = null,
    )

    data class Input(
        /** timeline 대상 사용자 (내 프로필이면 == viewerUserId). */
        val userId: Long,
        /** 요청 주체. `== userId` 면 self view (모든 activity), 아니면 visibility 필터. */
        val viewerUserId: Long,
        val windowDays: Int = DEFAULT_WINDOW_DAYS,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Result(
        val items: List<Item>,
        val windowDays: Int,
        val summary: ActivitySummary,
    )

    /** comment body 를 timeline preview 로 정규화 — 연속 whitespace 를 space 로 → trim → cap. */
    private fun previewOf(body: String): String {
        val normalized = body.replace(WHITESPACE_RUN, " ").trim()
        return if (normalized.length <= COMMENT_PREVIEW_MAX) normalized
               else normalized.take(COMMENT_PREVIEW_MAX - 1) + "…"
    }

    companion object {
        private const val DEFAULT_WINDOW_DAYS = 7
        private const val DEFAULT_LIMIT = 30
        private const val MAX_LIMIT = 100
        private const val COMMENT_PREVIEW_MAX = 60
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
