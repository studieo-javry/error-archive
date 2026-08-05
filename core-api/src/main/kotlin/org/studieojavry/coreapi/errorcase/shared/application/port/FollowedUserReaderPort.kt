package org.studieojavry.coreapi.errorcase.shared.application.port

/**
 * iam-api 의 `GET /internal/users/{userId}/following-ids` 호출.
 *
 * home dashboard 의 "Following · 새 케이스" feed 채울 때 followee userId list 필요.
 * 호출 실패는 *조용히 emptyList 반환* — feed 누락이 도메인 작업을 막지 않음.
 */
interface FollowedUserReaderPort {
    /** userId 가 *팔로우 중인* followee userIds. */
    fun findFollowingIds(userId: Long, size: Int = DEFAULT_SIZE): List<Long>

    companion object {
        const val DEFAULT_SIZE = 200
    }
}
