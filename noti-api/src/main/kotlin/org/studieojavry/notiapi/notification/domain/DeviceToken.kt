package org.studieojavry.notiapi.notification.domain

import java.time.Instant

/**
 * 사용자의 FCM 디바이스 토큰. user 가 *기기마다* 1건 등록 (앱 첫 실행/재설치 시).
 */
class DeviceToken private constructor(
    val id: Long?,
    val userId: Long,
    val token: String,
    val platform: Platform,
    val createdAt: Instant,
) {
    companion object {
        const val TOKEN_MAX = 512
        fun create(userId: Long, token: String, platform: Platform): DeviceToken {
            require(token.isNotBlank()) { "token must not be blank" }
            require(token.length <= TOKEN_MAX) { "token must be <= $TOKEN_MAX chars" }
            return DeviceToken(null, userId, token, platform, Instant.now())
        }
        fun rehydrate(id: Long, userId: Long, token: String, platform: Platform, createdAt: Instant) =
            DeviceToken(id, userId, token, platform, createdAt)
    }

    enum class Platform { IOS, ANDROID, WEB }
}
