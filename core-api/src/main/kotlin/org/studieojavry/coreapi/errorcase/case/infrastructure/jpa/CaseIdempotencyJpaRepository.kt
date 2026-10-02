package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseIdempotencyEntity
import java.time.Instant

interface CaseIdempotencyJpaRepository : JpaRepository<CaseIdempotencyEntity, Long> {

    fun findByIdempotencyKeyAndUserId(idempotencyKey: String, userId: Long): CaseIdempotencyEntity?

    @Modifying
    @Query("delete from CaseIdempotencyEntity e where e.createdAt < :cutoff")
    fun deleteCreatedBefore(@Param("cutoff") cutoff: Instant): Int
}
