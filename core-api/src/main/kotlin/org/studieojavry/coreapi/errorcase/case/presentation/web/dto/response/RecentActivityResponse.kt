package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActivitiesUseCase
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "Recent My Activities" timeline 항목.
 *
 * `caseTitle` 은 해당 케이스가 *현재 존재할 때만* 제공 — 삭제된 케이스의 활동이면 null.
 */
data class RecentActivityResponse(
    val type: String,
    val caseId: Long,
    val caseTitle: String?,
    val occurredAt: LocalDateTime,
) {
    companion object {
        fun from(i: GetMyRecentActivitiesUseCase.Item) = RecentActivityResponse(
            type = i.type.name,
            caseId = i.caseId,
            caseTitle = i.caseTitle,
            occurredAt = i.occurredAt,
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
