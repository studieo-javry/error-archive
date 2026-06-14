package org.studieojavry.notiapi.notification.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * 사용자 알림 설정 1 row. PK = userId (iam.user.id).
 * settings 는 JSON 문자열로 저장 — Adapter 에서 Jackson 으로 Domain ↔ String 변환.
 */
@Entity
@Table(name = "noti_user_notification_settings")
class NotificationSettingsEntity(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
    var settings: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)