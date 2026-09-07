package org.studieojavry.publishapi.publishment.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PublishIdempotencyJpaRepository : JpaRepository<PublishIdempotencyEntity, Long> {

    fun findByIdempotencyKeyAndUserId(idempotencyKey: String, userId: Long): PublishIdempotencyEntity?

    @Modifying
    @Query("delete from PublishIdempotencyEntity e where e.createdAt < :cutoff")
    fun deleteCreatedBefore(@Param("cutoff") cutoff: Instant): Int

    @Modifying
    @Query("delete from PublishIdempotencyEntity e where e.publishmentSlug = :slug")
    fun deleteByPublishmentSlug(@Param("slug") slug: String): Int
}
