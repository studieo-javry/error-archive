package org.studieojavry.notiapi.notification.application

import org.studieojavry.notiapi.notification.domain.Notification

interface NotificationRepositoryPort {
    fun save(n: Notification): Notification
    fun saveAll(ns: List<Notification>): List<Notification>

    fun findByUserId(userId: Long, unreadOnly: Boolean, limit: Int, offset: Int): List<Notification>
    fun countUnread(userId: Long): Long

    /** 본인 row 만 읽음 처리 (가드). 영향 row 수. */
    fun markRead(id: Long, userId: Long): Int
    fun markAllRead(userId: Long): Int
}