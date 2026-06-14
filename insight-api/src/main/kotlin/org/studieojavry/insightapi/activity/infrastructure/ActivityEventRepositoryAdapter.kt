package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.studieojavry.insightapi.activity.application.port.ActivityEventRepositoryPort
import org.studieojavry.insightapi.activity.domain.ActivityEvent
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityEventEntity

@Repository
class ActivityEventRepositoryAdapter(
    private val jpa: ActivityEventJpaRepository,
) : ActivityEventRepositoryPort {

    /**
     * 멱등 INSERT — UNIQUE(idempotency_key) 위반 시 `DataIntegrityViolationException` 캐치 → null.
     * pre-check(exists) 도 별도로 두지만, race 대비를 위해 catch 도 필요.
     */
    override fun insertIfAbsent(event: ActivityEvent): Long? {
        if (jpa.existsByIdempotencyKey(event.idempotencyKey)) return null
        return try {
            jpa.save(ActivityEventEntity.fromDomain(event)).id
        } catch (e: DataIntegrityViolationException) {
            null
        }
    }
}
