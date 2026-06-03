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
}
