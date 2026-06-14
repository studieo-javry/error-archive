package org.studieojavry.iamapi.auth.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.UserEntity
import java.time.Instant

interface UserJpaRepository : JpaRepository<UserEntity, Long> {
    fun findFirstByEmailIgnoreCase(email: String): UserEntity?

    /**
     * 회원 탈퇴 finalize 배치용. status + pendingDeletionAt < threshold 인 사용자를 오래된 순으로.
     */
    fun findByStatusAndPendingDeletionAtBeforeOrderByPendingDeletionAtAsc(
        status: UserStatus,
        threshold: Instant,
        pageable: Pageable,
    ): List<UserEntity>

    /** displayName prefix 검색 (대소문자 무시), ACTIVE 만. 가나다/abc 순. */
    fun findByStatusAndDisplayNameStartingWithIgnoreCaseOrderByDisplayNameAsc(
        status: UserStatus,
        prefix: String,
        pageable: Pageable,
    ): List<UserEntity>

    /** 빈 prefix 일 때 — 최근 가입한 ACTIVE 사용자. */
    fun findByStatusOrderByCreatedAtDesc(
        status: UserStatus,
        pageable: Pageable,
    ): List<UserEntity>

    /** 멘션 식별자 → userId 매핑용 (대소문자 무시 정확 일치). */
    fun findByStatusAndDisplayNameIgnoreCase(
        status: UserStatus,
        displayName: String,
    ): List<UserEntity>

    fun findAllByIdIn(ids: Collection<Long>): List<UserEntity>
}
