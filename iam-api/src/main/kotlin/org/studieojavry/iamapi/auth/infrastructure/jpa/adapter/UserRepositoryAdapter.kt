package org.studieojavry.iamapi.auth.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.auth.infrastructure.jpa.UserJpaRepository
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.UserEntity
import java.time.Instant

@Repository
class UserRepositoryAdapter(
    private val jpa: UserJpaRepository
) : UserRepositoryPort {

    override fun save(user: User): User {
        val entity = UserEntity.Companion.fromDomain(user)
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: Long): User? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findPendingDeletionBefore(threshold: Instant, limit: Int): List<User> =
        jpa.findByStatusAndPendingDeletionAtBeforeOrderByPendingDeletionAtAsc(
            UserStatus.PENDING_DELETION, threshold, PageRequest.of(0, limit)
        ).map { it.toDomain() }

    override fun searchByDisplayNamePrefix(prefix: String, limit: Int): List<User> {
        val cap = limit.coerceIn(1, 50)
        val pageable = PageRequest.of(0, cap)
        return if (prefix.isBlank()) {
            jpa.findByStatusOrderByCreatedAtDesc(UserStatus.ACTIVE, pageable)
        } else {
            jpa.findByStatusAndDisplayNameStartingWithIgnoreCaseOrderByDisplayNameAsc(
                UserStatus.ACTIVE, prefix, pageable
            )
        }.map { it.toDomain() }
    }

    override fun findActiveByDisplayName(displayName: String): List<User> =
        jpa.findByStatusAndDisplayNameIgnoreCase(UserStatus.ACTIVE, displayName).map { it.toDomain() }

    override fun findAllByIds(ids: Collection<Long>): List<User> =
        if (ids.isEmpty()) emptyList() else jpa.findAllByIdIn(ids).map { it.toDomain() }
}
