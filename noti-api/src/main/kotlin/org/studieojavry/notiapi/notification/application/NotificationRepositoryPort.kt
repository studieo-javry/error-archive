package org.studieojavry.notiapi.notification.application

import org.studieojavry.notiapi.notification.domain.Notification

interface NotificationRepositoryPort {
    fun save(n: Notification): Notification
    fun saveAll(ns: List<Notification>): List<Notification>

    fun findByUserId(userId: Long, unreadOnly: Boolean, limit: Int, offset: Int): List<Notification>

    /**
     * SSE 재연결 catchup — `id > sinceId` 인 row 만 (createdAt DESC, id DESC).
     * FE 가 마지막 받은 notification id 를 기억 → 재연결 시 그 이후 *놓친 알림* 만 한 번에 fetch.
     * unreadOnly 옵션과 공존 — 둘 다 적용 가능 (예: 읽지 않은 알림 중 놓친 것).
     */
    fun findByUserIdSince(userId: Long, sinceId: Long, unreadOnly: Boolean, limit: Int): List<Notification>

    fun countUnread(userId: Long): Long

    /** 본인 row 만 읽음 처리 (가드). 영향 row 수. */
    fun markRead(id: Long, userId: Long): Int
    fun markAllRead(userId: Long): Int
}