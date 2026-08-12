package org.studieojavry.notiapi.notification.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.studieojavry.notiapi.notification.domain.NotificationType
import java.time.Instant

@Entity
@Table(
    name = "noti_notification",
    indexes = [
        // inbox 조회: WHERE recipient_user_id = ? ORDER BY created_at DESC
        Index(name = "ix_noti_recipient_created", columnList = "recipient_user_id, created_at DESC"),
    ],
)
class NotificationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "recipient_user_id", nullable = false)
    var recipientUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    var type: NotificationType,

    @Column(name = "actor_user_id")
    var actorUserId: Long?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    var payload: String,

    @Column(name = "read_at")
    var readAt: Instant?,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)
