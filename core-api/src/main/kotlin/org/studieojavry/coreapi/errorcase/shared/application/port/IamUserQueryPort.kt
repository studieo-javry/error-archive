package org.studieojavry.coreapi.errorcase.shared.application.port

import org.springframework.stereotype.Repository

/**
 * iam-api 의 user 도메인 조회 — cross-aggregate.
 *
 * - searchByDisplayNamePrefix: 멘션 자동완성 후보 풀.
 * - resolveByDisplayName: 댓글 본문의 @식별자 → userId 매핑. 동명이인이면 null.
 * - findByIds: 알림 발송 시 표시 정보 batch 조회.
 *
 * iam-api 가 다운/지연되면 CircuitBreaker 가 빈 결과/null 로 graceful fallback.
 */
@Repository
interface IamUserQueryPort {
    fun searchByDisplayNamePrefix(q: String, limit: Int): List<UserSummary>

    /** displayName 정확 일치(대소문자 무시) 사용자가 *정확히 1명* 이면 그 userId, 아니면 null. */
    fun resolveByDisplayName(displayName: String): Long?

    fun findByIds(ids: Collection<Long>): List<UserSummary>

    data class UserSummary(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
    )
}