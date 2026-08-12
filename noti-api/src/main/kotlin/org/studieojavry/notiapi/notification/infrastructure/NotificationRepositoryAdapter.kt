package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.studieojavry.notiapi.notification.application.NotificationRepositoryPort
import org.studieojavry.notiapi.notification.domain.Notification
import java.time.Instant

@Repository
class NotificationRepositoryAdapter(
    private val jpa: NotificationJpaRepository,
) : NotificationRepositoryPort {

    override fun save(n: Notification): Notification = toDomain(jpa.save(toEntity(n)))

    override fun saveAll(ns: List<Notification>): List<Notification> {
        if (ns.isEmpty()) return emptyList()
        return jpa.saveAll(ns.map { toEntity(it) }).map { toDomain(it) }
    }

    override fun findByUserId(userId: Long, unreadOnly: Boolean, limit: Int, offset: Int): List<Notification> {
        val pageable = PageRequest.of((offset / limit.coerceAtLeast(1)), limit.coerceIn(1, 100))
        val rows = if (unreadOnly) {
            jpa.findAllByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, pageable)
        } else {
            jpa.findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(userId, pageable)
        }
        return rows.map { toDomain(it) }
    }

    override fun findByUserIdSince(userId: Long, sinceId: Long, unreadOnly: Boolean, limit: Int): List<Notification> {
        val pageable = PageRequest.of(0, limit.coerceIn(1, 100))
        val rows = if (unreadOnly) {
            jpa.findAllByRecipientUserIdAndIdGreaterThanAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, sinceId, pageable)
        } else {
            jpa.findAllByRecipientUserIdAndIdGreaterThanOrderByCreatedAtDescIdDesc(userId, sinceId, pageable)
        }
        return rows.map { toDomain(it) }
    }

    override fun countUnread(userId: Long): Long =
        jpa.countByRecipientUserIdAndReadAtIsNull(userId)

    override fun markRead(id: Long, userId: Long): Int =
        jpa.markReadIfOwner(id, userId, Instant.now())

    override fun markAllRead(userId: Long): Int =
        jpa.markAllReadByUser(userId, Instant.now())

    private fun toEntity(n: Notification) = NotificationEntity(
        id = n.id, recipientUserId = n.recipientUserId, type = n.type,
        actorUserId = n.actorUserId, payload = n.payload, readAt = n.readAt, createdAt = n.createdAt,
    )
    private fun toDomain(e: NotificationEntity) = Notification.rehydrate(
        id = e.id!!, recipientUserId = e.recipientUserId, type = e.type,
        actorUserId = e.actorUserId, payload = e.payload, readAt = e.readAt, createdAt = e.createdAt,
    )
}