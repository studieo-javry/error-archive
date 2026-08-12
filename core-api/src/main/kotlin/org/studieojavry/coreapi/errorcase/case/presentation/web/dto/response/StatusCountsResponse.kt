package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

/**
 * Library > My cases 페이지의 status filter chip 카운트 응답.
 *
 * `byStatus` — 3 status (OPEN / IN_PROGRESS / RESOLVED) 모두 포함 (없어도 0).
 * `total` — 3 개 status 합계 (= q 필터 통과한 전체 case 수).
 */
data class StatusCountsResponse(
    val byStatus: Map<String, Long>,
    val total: Long,
)
