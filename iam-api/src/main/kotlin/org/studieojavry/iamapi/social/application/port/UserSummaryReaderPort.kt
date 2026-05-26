package org.studieojavry.iamapi.social.application.port

/**
 * social 컨텍스트가 auth 컨텍스트의 User 정보를 읽을 때 통과하는 경계.
 * auth.User 도메인 객체를 직접 노출하지 않고 사회 그래프 표시에 필요한 최소 정보만 가져온다.
 */
interface UserSummaryReaderPort {

    fun existsActive(userId: Long): Boolean

    fun findSummaries(userIds: List<Long>): List<UserSummary>

    data class UserSummary(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?
    )
}
