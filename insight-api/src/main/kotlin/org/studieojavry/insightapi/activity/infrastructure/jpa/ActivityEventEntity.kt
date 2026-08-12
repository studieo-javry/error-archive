package org.studieojavry.insightapi.activity.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.studieojavry.insightapi.activity.domain.ActivityEvent
import org.studieojavry.insightapi.activity.domain.ActivityType
import java.time.Instant

@Entity
@Table(
    name = "insight_activity_event",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_activity_event_idem",
        columnNames = ["idempotency_key"]
    )],
    indexes = [Index(name = "ix_activity_event_user_time", columnList = "user_id, occurred_at")],
)
class ActivityEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    var type: ActivityType,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant,

    @Column(name = "score", nullable = false)
    var score: Int,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    var metaJson: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    fun toDomain(): ActivityEvent = ActivityEvent.Companion.rehydrate(
        id!!, userId, type, occurredAt, score, idempotencyKey, metaJson, createdAt,
    )

    companion object {
        fun fromDomain(e: ActivityEvent) = ActivityEventEntity(
            id = e.id, userId = e.userId, type = e.type,
            occurredAt = e.occurredAt, score = e.score,
            idempotencyKey = e.idempotencyKey, metaJson = e.metaJson,
            createdAt = e.createdAt,
        )
    }
}
