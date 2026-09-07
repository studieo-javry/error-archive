package org.studieojavry.publishapi.publishment.presentation.dto

import org.studieojavry.publishapi.publishment.application.port.OwnerPublishmentSummary

/**
 * Library > Published 툴바 요약 — `N published · N public · N unlisted · Nk views`.
 * 검색/필터와 무관하게 소유자 **전체 발행물** 기준. `total = public + unlisted + private`.
 */
data class PublishmentSummaryResponse(
    val total: Long,
    val publicCount: Long,
    val unlistedCount: Long,
    val totalViews: Long,
    val totalDownloads: Long,
) {
    companion object {
        fun from(s: OwnerPublishmentSummary) = PublishmentSummaryResponse(
            total = s.total,
            publicCount = s.publicCount,
            unlistedCount = s.unlistedCount,
            totalViews = s.totalViews,
            totalDownloads = s.totalDownloads,
        )
    }
}
