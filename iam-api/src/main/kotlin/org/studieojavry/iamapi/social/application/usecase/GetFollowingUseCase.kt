package org.studieojavry.iamapi.social.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort

@Service
class GetFollowingUseCase(
    private val followRepository: FollowRepositoryPort,
    private val userSummaryReader: UserSummaryReaderPort
) {

    @Transactional(readOnly = true)
    fun invoke(userId: Long, page: Int, size: Int, viewerUserId: Long? = null): Page {
        if (!userSummaryReader.existsActive(userId)) {
            throw NoSuchElementException("user not found: $userId")
        }
        val ids = followRepository.findFollowingIds(userId, page, size)
        val summaries = userSummaryReader.findSummaries(ids.items)
        val byId = summaries.associateBy { it.userId }
        val ordered = ids.items.mapNotNull { byId[it] }
        // viewer 가 이 페이지의 각 사용자를 팔로우 중인지 한 번의 batch query 로 조회(N+1 회피).
        // 비로그인(viewerUserId=null)이면 전부 false → FE 에서 전부 'Follow' 로 표시.
        val followedByViewer = if (viewerUserId != null) {
            followRepository.existsAll(viewerUserId, ordered.map { it.userId })
        } else emptySet()
        return Page(
            items = ordered.map {
                Item(
                    userId = it.userId,
                    handle = it.handle,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl,
                    isFollowing = it.userId in followedByViewer,
                    isSelf = it.userId == viewerUserId,
                )
            },
            page = ids.page,
            size = ids.size,
            totalElements = ids.totalElements,
            totalPages = ids.totalPages
        )
    }

    data class Item(
        val userId: Long,
        val handle: String,
        val displayName: String,
        val avatarUrl: String?,
        /** viewer 가 이 사용자를 팔로우 중인지. 비로그인이면 false. */
        val isFollowing: Boolean,
        /** 이 사용자가 viewer 본인인지(본인 행은 FE 에서 버튼 숨김). */
        val isSelf: Boolean,
    )
    data class Page(
        val items: List<Item>,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int
    )
}
