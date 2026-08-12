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
import jakarta.persistence.UniqueConstraint
import org.studieojavry.notiapi.notification.domain.DeviceToken
import java.time.Instant

@Entity
@Table(
    name = "noti_device_token",
    uniqueConstraints = [UniqueConstraint(name = "uq_noti_device_token", columnNames = ["user_id", "token"])],
    indexes = [Index(name = "ix_noti_device_token_user", columnList = "user_id")],
)
class DeviceTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "token", nullable = false, length = 512)
    var token: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    var platform: DeviceToken.Platform,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
) {
    fun toDomain(): DeviceToken = DeviceToken.rehydrate(id!!, userId, token, platform, createdAt)
    companion object {
        fun fromDomain(d: DeviceToken) = DeviceTokenEntity(
            id = d.id, userId = d.userId, token = d.token, platform = d.platform, createdAt = d.createdAt,
        )
    }
}
