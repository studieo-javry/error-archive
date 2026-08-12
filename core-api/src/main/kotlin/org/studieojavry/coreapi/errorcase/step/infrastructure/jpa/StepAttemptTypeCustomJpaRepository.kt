package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity.StepAttemptTypeCustomEntity

interface StepAttemptTypeCustomJpaRepository : JpaRepository<StepAttemptTypeCustomEntity, Long> {

    fun findAllByUserIdOrderByCreatedAtAscIdAsc(userId: Long): List<StepAttemptTypeCustomEntity>

    fun findByUserIdAndNormalized(userId: Long, normalized: String): StepAttemptTypeCustomEntity?

    @Modifying(clearAutomatically = true)
    @Query("delete from StepAttemptTypeCustomEntity c where c.userId = :userId and c.normalized = :normalized")
    fun deleteByUserIdAndNormalized(@Param("userId") userId: Long, @Param("normalized") normalized: String): Int
}
