package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.stereotype.Repository
import org.studieojavry.notiapi.notification.application.NotificationSettingsRepositoryPort
import org.studieojavry.notiapi.notification.domain.NotificationSettings
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Repository
class NotificationSettingsRepositoryAdapter(
    private val jpa: NotificationSettingsJpaRepository,
    private val objectMapper: ObjectMapper,
) : NotificationSettingsRepositoryPort {

    override fun findByUserId(userId: Long): NotificationSettings? =
        jpa.findById(userId).orElse(null)?.let {
            objectMapper.readValue(it.settings, NotificationSettings::class.java)
        }

    override fun upsert(userId: Long, settings: NotificationSettings) {
        val json = objectMapper.writeValueAsString(settings)
        val now = Instant.now()
        val existing = jpa.findById(userId).orElse(null)
        if (existing == null) {
            jpa.save(NotificationSettingsEntity(
                userId = userId,
                settings = json,
                createdAt = now,
                updatedAt = now,
            ))
        } else {
            existing.settings = json
            existing.updatedAt = now
            jpa.save(existing)
        }
    }
}