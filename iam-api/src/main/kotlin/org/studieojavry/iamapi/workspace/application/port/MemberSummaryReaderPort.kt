package org.studieojavry.iamapi.workspace.application.port

/**
 * workspace 컨텍스트가 auth.User를 직접 노출하지 않기 위한 cross-context 경계.
 * 멤버 목록에 표시할 최소 정보(프로필 버튼 navigate 포함)만 가져온다.
 */
interface MemberSummaryReaderPort {

    fun existsActive(userId: Long): Boolean

    fun findSummaries(userIds: Collection<Long>): List<MemberSummary>

    fun findActiveByEmail(email: String): MemberSummary?

    data class MemberSummary(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val email: String?
    )
}