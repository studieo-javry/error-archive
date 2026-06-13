package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

interface NotificationSettingsJpaRepository : JpaRepository<NotificationSettingsEntity, Long>