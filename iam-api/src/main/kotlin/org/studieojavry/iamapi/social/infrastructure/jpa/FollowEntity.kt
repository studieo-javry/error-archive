package org.studieojavry.iamapi.social.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.iamapi.social.domain.model.Follow
import java.time.Instant

@Entity
@Table(
    name = "iam_follow",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_iam_follow_follower_followee",
            columnNames = ["follower_id", "followee_id"]
        )
    ],
    indexes = [
        Index(name = "ix_iam_follow_followee", columnList = "followee_id"),
        Index(name = "ix_iam_follow_follower", columnList = "follower_id")
    ]
)
class FollowEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "follower_id", nullable = false)
    var followerId: Long,

    @Column(name = "followee_id", nullable = false)
    var followeeId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant
) {

    fun toDomain(): Follow = Follow.rehydrate(
        id = id!!,
        followerId = followerId,
        followeeId = followeeId,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(follow: Follow): FollowEntity = FollowEntity(
            id = follow.id,
            followerId = follow.followerId,
            followeeId = follow.followeeId,
            createdAt = follow.createdAt
        )
    }
}
