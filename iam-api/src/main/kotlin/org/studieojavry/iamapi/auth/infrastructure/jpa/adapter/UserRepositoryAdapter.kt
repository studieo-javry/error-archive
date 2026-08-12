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

    override fun existsByHandle(handle: String): Boolean = jpa.existsByHandleIgnoreCase(handle)

    override fun findPendingDeletionBefore(threshold: Instant, limit: Int): List<User> =
        jpa.findByStatusAndPendingDeletionAtBeforeOrderByPendingDeletionAtAsc(
            UserStatus.PENDING_DELETION, threshold, PageRequest.of(0, limit)
        ).map { it.toDomain() }

    override fun searchByDisplayNamePrefix(prefix: String, limit: Int): List<User> {
        val cap = limit.coerceIn(1, 50)
        val pageable = PageRequest.of(0, cap)
        if (prefix.isBlank()) {
            return jpa.findByStatusOrderByCreatedAtDesc(UserStatus.ACTIVE, pageable).map { it.toDomain() }
        }
        // handle 매칭 우선 (unique → 정확도 ↑) + display 매칭 보조. 중복 제거 후 limit 까지.
        val byHandle = jpa.findByStatusAndHandleStartingWithIgnoreCaseOrderByHandleAsc(
            UserStatus.ACTIVE, prefix, pageable
        )
        val byDisplay = jpa.findByStatusAndDisplayNameStartingWithIgnoreCaseOrderByDisplayNameAsc(
            UserStatus.ACTIVE, prefix, pageable
        )
        val seen = LinkedHashMap<Long, UserEntity>()
        (byHandle + byDisplay).forEach { e -> e.id?.let { seen.putIfAbsent(it, e) } }
        return seen.values.take(cap).map { it.toDomain() }
    }

    override fun findActiveByDisplayName(displayName: String): List<User> =
        jpa.findByStatusAndDisplayNameIgnoreCase(UserStatus.ACTIVE, displayName).map { it.toDomain() }

    override fun findAllByIds(ids: Collection<Long>): List<User> =
        if (ids.isEmpty()) emptyList() else jpa.findAllByIdIn(ids).map { it.toDomain() }
}
