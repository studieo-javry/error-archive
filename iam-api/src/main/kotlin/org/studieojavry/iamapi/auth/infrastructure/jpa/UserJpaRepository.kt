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
}
