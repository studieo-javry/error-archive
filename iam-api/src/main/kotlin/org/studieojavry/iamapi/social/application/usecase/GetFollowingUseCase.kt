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
    fun invoke(userId: Long, page: Int, size: Int): Page {
        if (!userSummaryReader.existsActive(userId)) {
            throw NoSuchElementException("user not found: $userId")
        }
        val ids = followRepository.findFollowingIds(userId, page, size)
        val summaries = userSummaryReader.findSummaries(ids.items)
        val byId = summaries.associateBy { it.userId }
        val ordered = ids.items.mapNotNull { byId[it] }
        return Page(
            items = ordered.map { Item(it.userId, it.handle, it.displayName, it.avatarUrl) },
            page = ids.page,
            size = ids.size,
            totalElements = ids.totalElements,
            totalPages = ids.totalPages
        )
    }

    data class Item(val userId: Long, val handle: String, val displayName: String, val avatarUrl: String?)
    data class Page(
        val items: List<Item>,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int
    )
}
