package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.step.application.port.StepAttemptTypeCatalogPort
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.StepAttemptTypeCustomJpaRepository
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepAttemptTypeCustomEntity

@Component
class StepAttemptTypeCatalogAdapter(
    private val jpa: StepAttemptTypeCustomJpaRepository,
) : StepAttemptTypeCatalogPort {

    override fun findAllByUserId(userId: Long): List<String> =
        jpa.findAllByUserIdOrderByCreatedAtAscIdAsc(userId).map { it.name }

    override fun add(userId: Long, name: String, normalized: String): Boolean {
        if (jpa.findByUserIdAndNormalized(userId, normalized) != null) return false
        jpa.save(StepAttemptTypeCustomEntity(userId = userId, name = name, normalized = normalized))
        return true
    }

    override fun remove(userId: Long, normalized: String): Int =
        jpa.deleteByUserIdAndNormalized(userId, normalized)

    override fun exists(userId: Long, normalized: String): Boolean =
        jpa.findByUserIdAndNormalized(userId, normalized) != null
}
