package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityEventEntity

interface ActivityEventJpaRepository : JpaRepository<ActivityEventEntity, Long> {
    fun existsByIdempotencyKey(key: String): Boolean
}
