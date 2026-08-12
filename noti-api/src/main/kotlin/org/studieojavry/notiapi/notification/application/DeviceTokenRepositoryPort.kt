package org.studieojavry.notiapi.notification.application

import org.studieojavry.notiapi.notification.domain.DeviceToken

interface DeviceTokenRepositoryPort {
    /** 멱등 등록 — 동일 (userId, token) 있으면 그대로 반환, 없으면 새로 저장. */
    fun register(userId: Long, token: String, platform: DeviceToken.Platform): DeviceToken
    fun listByUserId(userId: Long): List<DeviceToken>
    fun remove(userId: Long, token: String): Int
}