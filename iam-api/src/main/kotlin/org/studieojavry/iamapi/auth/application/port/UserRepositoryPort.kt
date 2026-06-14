package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.User
import java.time.Instant

interface UserRepositoryPort {
    fun save(user: User): User
    fun findById(id: Long): User?

    /**
     * 회원 탈퇴 finalize 배치용. `pendingDeletionAt` 이 threshold 이전인 PENDING_DELETION 사용자를 limit 만큼.
     */
    fun findPendingDeletionBefore(threshold: Instant, limit: Int): List<User>

    /**
     * displayName prefix 검색 (ACTIVE 사용자만). 대소문자 무시.
     * 멘션 자동완성용. 빈 prefix 면 *최근 ACTIVE 가입자* 순으로 limit 만큼.
     */
    fun searchByDisplayNamePrefix(prefix: String, limit: Int): List<User>

    /**
     * displayName 완전 일치(대소문자 무시) ACTIVE 사용자 — 멘션 식별자 → userId 매핑용.
     * 동명이인 가능성을 고려해 *List* 반환. 호출자가 정책 결정.
     */
    fun findActiveByDisplayName(displayName: String): List<User>

    /** 여러 userId 한 번에 조회 — 알림 발송 등 batch 호출에 사용. */
    fun findAllByIds(ids: Collection<Long>): List<User>
}
