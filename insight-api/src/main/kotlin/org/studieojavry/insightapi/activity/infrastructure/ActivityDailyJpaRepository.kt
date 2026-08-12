package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityDailyEntity
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityDailyKey
import java.time.LocalDate

interface ActivityDailyJpaRepository : JpaRepository<ActivityDailyEntity, ActivityDailyKey> {
    @Query("""
        select d from ActivityDailyEntity d
        where d.userId = :userId
          and d.activityDate >= :from
          and d.activityDate <= :to
        order by d.activityDate asc
    """)
    fun findInRange(
        @Param("userId") userId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<ActivityDailyEntity>

    /**
     * breakdown_json 의 특정 type count 누적 합. 사용자 전 기간.
     * (jsonb ->> 'TYPE')::int — 키가 없으면 NULL → COALESCE 0.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM((breakdown_json ->> :type)::int), 0)
            FROM insight_activity_daily
            WHERE user_id = :userId
        """,
        nativeQuery = true,
    )
    fun sumCountByType(
        @Param("userId") userId: Long,
        @Param("type") type: String,
    ): Long

    /**
     * breakdown_json 의 특정 type count 누적 합 — activity_date >= since 만.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM((breakdown_json ->> :type)::int), 0)
            FROM insight_activity_daily
            WHERE user_id = :userId AND activity_date >= :since
        """,
        nativeQuery = true,
    )
    fun sumCountByTypeSince(
        @Param("userId") userId: Long,
        @Param("type") type: String,
        @Param("since") since: LocalDate,
    ): Long
}
