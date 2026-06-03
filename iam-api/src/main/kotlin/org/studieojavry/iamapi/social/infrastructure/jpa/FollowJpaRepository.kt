package org.studieojavry.iamapi.social.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FollowJpaRepository : JpaRepository<FollowEntity, Long> {

    fun existsByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long): Boolean

    @Modifying(clearAutomatically = true)
    @Query(
        """
        delete from FollowEntity f
         where f.followerId = :followerId
           and f.followeeId = :followeeId
        """
    )
    fun deleteRelation(@Param("followerId") followerId: Long, @Param("followeeId") followeeId: Long): Int

    /** 회원 탈퇴 시: 사용자가 follower 또는 followee 인 모든 엣지 삭제. */
    @Modifying(clearAutomatically = true)
    @Query("delete from FollowEntity f where f.followerId = :userId or f.followeeId = :userId")
    fun deleteAllByUser(@Param("userId") userId: Long): Int

    fun countByFolloweeId(followeeId: Long): Long
    fun countByFollowerId(followerId: Long): Long

    @Query(
        """
        select f.followerId from FollowEntity f
         where f.followeeId = :userId
         order by f.createdAt desc, f.id desc
        """
    )
    fun findFollowerIds(@Param("userId") userId: Long, pageable: Pageable): List<Long>

    @Query(
        """
        select f.followeeId from FollowEntity f
         where f.followerId = :userId
         order by f.createdAt desc, f.id desc
        """
    )
    fun findFollowingIds(@Param("userId") userId: Long, pageable: Pageable): List<Long>
}
