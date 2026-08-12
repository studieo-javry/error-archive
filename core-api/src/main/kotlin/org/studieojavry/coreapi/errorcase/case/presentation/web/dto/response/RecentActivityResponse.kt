package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActivitiesUseCase
import java.time.LocalDateTime

/**
 * my-page "Recent activities" timeline 항목.
 *
 * `caseTitle` 은 해당 케이스가 *현재 존재할 때만* 제공 — 삭제된 케이스의 활동이면 null.
 *
 * **type-specific enrichment** 필드는 해당 type 일 때만 채워짐:
 *  - COMMENT_POSTED → `commentPreview` (앞 60자, 공백 정규화), `commentIsReply`
 *  - STEP_ADDED → `stepTitle`, `stepAttemptType` (null 가능)
 *  - SOLUTION_REGISTERED → `solutionTitle`, `solutionStepCount`
 *  - CASE_CREATED / CASE_RESOLVED → enrichment 필드 모두 null
 */
data class RecentActivityResponse(
    val type: String,
    val caseId: Long,
    val caseTitle: String?,
    val occurredAt: LocalDateTime,
    val commentPreview: String? = null,
    val commentIsReply: Boolean = false,
    val stepTitle: String? = null,
    val stepAttemptType: String? = null,
    val solutionTitle: String? = null,
    val solutionStepCount: Int? = null,
    val resolvedAfterSteps: Int? = null,
    /** case-level. All types 공통 — FE badge 렌더용. PUBLIC / WORKSPACE / PRIVATE. */
    val caseVisibility: String? = null,
    val caseWorkspaceId: Long? = null,
    /** viewer 가 그 workspace 멤버일 때만 채워짐. non-member 면 null (badge 는 workspaceId 만으로 미표시). */
    val caseWorkspaceName: String? = null,
) {
    companion object {
        fun from(i: GetMyRecentActivitiesUseCase.Item) = RecentActivityResponse(
            type = i.type.name,
            caseId = i.caseId,
            caseTitle = i.caseTitle,
            occurredAt = i.occurredAt,
            commentPreview = i.commentPreview,
            commentIsReply = i.commentIsReply,
            stepTitle = i.stepTitle,
            stepAttemptType = i.stepAttemptType,
            solutionTitle = i.solutionTitle,
            solutionStepCount = i.solutionStepCount,
            resolvedAfterSteps = i.resolvedAfterSteps,
            caseVisibility = i.caseVisibility,
            caseWorkspaceId = i.caseWorkspaceId,
            caseWorkspaceName = i.caseWorkspaceName,
        )
    }
}

/**
 * `summary` 는 `windowDays` *전체* 의 type 별 활동 합계 — items 의 limit 와 무관.
 * mock 의 "2 resolved · 1 published · 3 steps" pills 표시용. items 는 그 안에서 최근 limit 개.
 */
data class RecentActivitiesListResponse(
    val items: List<RecentActivityResponse>,
    val windowDays: Int,
    val summary: ActivitySummaryResponse,
)

data class ActivitySummaryResponse(
    val byType: Map<String, Long>,
)
