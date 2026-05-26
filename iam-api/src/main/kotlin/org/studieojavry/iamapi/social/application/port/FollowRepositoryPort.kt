package org.studieojavry.iamapi.social.application.port

import org.studieojavry.iamapi.social.domain.model.Follow

interface FollowRepositoryPort {

    fun save(follow: Follow): Follow

    fun exists(followerId: Long, followeeId: Long): Boolean

    fun delete(followerId: Long, followeeId: Long): Boolean

    /** 회원 탈퇴 시 사용자의 follower/followee 양방향 모든 엣지 삭제. */
    fun deleteAllByUser(userId: Long): Int

    fun countFollowers(userId: Long): Long

    fun countFollowing(userId: Long): Long

    /**
     * userId를 팔로우하는 follower들의 userId를 페이지 단위로 반환.
     */
    fun findFollowerIds(userId: Long, page: Int, size: Int): PagedResult<Long>

    /**
     * userId가 팔로우 중인 followee들의 userId를 페이지 단위로 반환.
     */
    fun findFollowingIds(userId: Long, page: Int, size: Int): PagedResult<Long>

    data class PagedResult<T>(
        val items: List<T>,
        val page: Int,
        val size: Int,
        val totalElements: Long
    ) {
        val totalPages: Int = if (size <= 0) 0 else ((totalElements + size - 1) / size).toInt()
    }
}
