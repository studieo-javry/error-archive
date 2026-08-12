package org.studieojavry.iamapi.auth.application.port

import org.studieojavry.iamapi.auth.domain.model.User
import java.time.Instant

interface UserRepositoryPort {
    fun save(user: User): User
    fun findById(id: Long): User?

    /**
     * OAuth callback 시 handle 매핑 — 동일 handle 이 이미 있는지 검사 (대소문자 무시).
     * 충돌하면 호출자가 suffix `-2`, `-3` 으로 회피.
     */
    fun existsByHandle(handle: String): Boolean

    /**
     * 회원 탈퇴 finalize 배치용. `pendingDeletionAt` 이 threshold 이전인 PENDING_DELETION 사용자를 limit 만큼.
     */
    fun findPendingDeletionBefore(threshold: Instant, limit: Int): List<User>

    /**
     * displayName **또는 handle** prefix 검색 (ACTIVE 사용자만). 대소문자 무시.
     * 멘션 자동완성용. 빈 prefix 면 *최근 ACTIVE 가입자* 순으로 limit 만큼.
     * handle 매칭 결과를 displayName 매칭보다 우선 배치 (handle 이 unique 라 정확도 ↑).
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
