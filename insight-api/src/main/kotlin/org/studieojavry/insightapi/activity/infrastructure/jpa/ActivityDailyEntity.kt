package org.studieojavry.insightapi.activity.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(
    name = "insight_activity_daily",
    indexes = [Index(name = "ix_activity_daily_user_date", columnList = "user_id, activity_date DESC")],
)
@IdClass(ActivityDailyKey::class)
class ActivityDailyEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Id
    @Column(name = "activity_date", nullable = false)
    var activityDate: LocalDate,

    @Column(name = "event_count", nullable = false)
    var eventCount: Int,

    @Column(name = "score_sum", nullable = false)
    var scoreSum: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown_json", nullable = false, columnDefinition = "jsonb")
    var breakdownJson: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

data class ActivityDailyKey(
    var userId: Long = 0,
    var activityDate: LocalDate = LocalDate.MIN,
) : Serializable
