package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActiveCasesUseCase
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "My Recent Active Cases" 카드.
 *
 * `lastActivityAt` 은 case 자체 updatedAt + comment/step/solution 의 max createdAt.
 * `commentCount` 는 deleted 제외. step/solution count 는 단순 row 수.
 */
data class RecentActiveCaseResponse(
    val id: Long,
    val title: String,
    val status: String,
    val visibility: String,
    val workspaceId: Long?,
    val stepCount: Long,
    val commentCount: Long,
    val solutionCount: Long,
    val meTooCount: Long,
    val createdAt: LocalDateTime,
    val lastActivityAt: LocalDateTime,
    /** 마지막 view 이후 다른 사람 활동 수. 본인이 한 활동은 제외. 한 번도 안 봤으면 전체 활동 수. */
    val unreadCount: Long,
    /** 지난 24h 새 댓글 수 (작성자 무관). FE 가 lastActivityAt 신선도에 따라 24h vs 7d 표시 선택. */
    val commentDelta24h: Long,
    /** 지난 7d 새 댓글 수. */
    val commentDelta7d: Long,
    /** 지난 24h 새 me-too 수. */
    val meTooDelta24h: Long,
    /** 지난 7d 새 me-too 수. */
    val meTooDelta7d: Long,
) {
    companion object {
        fun from(i: GetMyRecentActiveCasesUseCase.Item) = RecentActiveCaseResponse(
            id = i.summary.id,
            title = i.summary.title,
            status = i.summary.status.name,
            visibility = i.summary.visibility.name,
            workspaceId = i.summary.workspaceId,
            stepCount = i.stepCount,
            commentCount = i.commentCount,
            solutionCount = i.solutionCount,
            meTooCount = i.meTooCount,
            createdAt = i.summary.createdAt,
            lastActivityAt = i.lastActivityAt,
            unreadCount = i.unreadCount,
            commentDelta24h = i.commentDelta24h,
            commentDelta7d = i.commentDelta7d,
            meTooDelta24h = i.meTooDelta24h,
            meTooDelta7d = i.meTooDelta7d,
        )
    }
}

data class RecentActiveCasesListResponse(
    val items: List<RecentActiveCaseResponse>,
)
