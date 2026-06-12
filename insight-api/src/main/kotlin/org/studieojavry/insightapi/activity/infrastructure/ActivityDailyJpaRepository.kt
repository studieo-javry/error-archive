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
}
