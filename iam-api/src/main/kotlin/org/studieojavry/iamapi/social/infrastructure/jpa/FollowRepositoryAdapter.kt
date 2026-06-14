package org.studieojavry.iamapi.social.infrastructure.jpa

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort.PagedResult
import org.studieojavry.iamapi.social.domain.model.Follow

@Repository
class FollowRepositoryAdapter(
    private val jpa: FollowJpaRepository
) : FollowRepositoryPort {

    override fun save(follow: Follow): Follow =
        jpa.save(FollowEntity.fromDomain(follow)).toDomain()

    override fun exists(followerId: Long, followeeId: Long): Boolean =
        jpa.existsByFollowerIdAndFolloweeId(followerId, followeeId)

    override fun existsAll(followerId: Long, followeeIds: Collection<Long>): Set<Long> {
        if (followeeIds.isEmpty()) return emptySet()
        return jpa.findFollowedIds(followerId, followeeIds).toSet()
    }

    override fun delete(followerId: Long, followeeId: Long): Boolean =
        jpa.deleteRelation(followerId, followeeId) > 0

    override fun deleteAllByUser(userId: Long): Int = jpa.deleteAllByUser(userId)

    override fun countFollowers(userId: Long): Long = jpa.countByFolloweeId(userId)

    override fun countFollowing(userId: Long): Long = jpa.countByFollowerId(userId)

    override fun findFollowerIds(userId: Long, page: Int, size: Int): PagedResult<Long> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
        val items = jpa.findFollowerIds(userId, pageable)
        val total = jpa.countByFolloweeId(userId)
        return PagedResult(items, pageable.pageNumber, pageable.pageSize, total)
    }

    override fun findFollowingIds(userId: Long, page: Int, size: Int): PagedResult<Long> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
        val items = jpa.findFollowingIds(userId, pageable)
        val total = jpa.countByFollowerId(userId)
        return PagedResult(items, pageable.pageNumber, pageable.pageSize, total)
    }
}
