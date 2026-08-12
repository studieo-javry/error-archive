package org.studieojavry.coreapi.errorcase.case.application.port

/**
 * 에러 케이스 목록 응답에 *작성자 정보* 를 enrichment 하기 위한 port.
 *
 * 검색/목록 결과의 ownerUserId 들을 모아 한 번에 iam-api 로 요청 → 응답 항목 hydration.
 * - viewerUserId 가 null 이면 isFollowing 은 모두 false
 * - 비활성/삭제 사용자는 결과에서 제외 (Map 에 key 부재)
 */
interface AuthorSummaryReaderPort {

    fun read(authorUserIds: Collection<Long>, viewerUserId: Long?): Map<Long, AuthorSummary>

    data class AuthorSummary(
        val userId: Long,
        val handle: String,
        val displayName: String,
        val avatarUrl: String?,
        val bio: String?,
        val isFollowing: Boolean,
    )
}
