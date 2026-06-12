package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityEventEntity
import java.time.Instant

interface ActivityEventJpaRepository : JpaRepository<ActivityEventEntity, Long> {
    fun existsByIdempotencyKey(key: String): Boolean

    /**
     * TTL 만료 raw 를 *batch 단위로* DELETE — 한 트랜잭션에 너무 많이 잡으면 lock 길어짐.
     * 한 라운드에 [limit] 건 삭제. cleanup 잡이 0 반환까지 반복 호출.
     */
    @Modifying
    @Query(
        value = """
            DELETE FROM insight_activity_event
             WHERE id IN (
                SELECT id FROM insight_activity_event
                 WHERE created_at < :before
                 ORDER BY id
                 LIMIT :limit
             )
        """,
        nativeQuery = true,
    )
    fun deleteOlderThan(@Param("before") before: Instant, @Param("limit") limit: Int): Int
}
