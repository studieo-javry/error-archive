package org.studieojavry.iamapi.social.domain.model

import java.time.Instant

class Follow private constructor(
    val id: Long?,
    val followerId: Long,
    val followeeId: Long,
    val createdAt: Instant
) {
    init {
        require(followerId != followeeId) { "cannot follow self" }
    }

    companion object {
        fun create(followerId: Long, followeeId: Long): Follow =
            Follow(
                id = null,
                followerId = followerId,
                followeeId = followeeId,
                createdAt = Instant.now()
            )

        fun rehydrate(
            id: Long,
            followerId: Long,
            followeeId: Long,
            createdAt: Instant
        ): Follow = Follow(id, followerId, followeeId, createdAt)
    }
}
