package org.studieojavry.notiapi.notification.infrastructure.sender

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "noti.email")
data class EmailProperties(
    /** smtp | logging */
    val provider: String = "logging",
    val fromAddress: String = "notifications@error-archive.local",
    val fromName: String = "Error Archive",
    val subjectPrefix: String = "[Error Archive]",
)

@ConfigurationProperties(prefix = "noti.push")
data class PushProperties(
    /** fcm | logging */
    val provider: String = "logging",
    val fcm: Fcm = Fcm(),
) {
    data class Fcm(
        /** FCM project id (HTTP v1). */
        val projectId: String = "",
        /** 서비스 계정 키 JSON 파일 경로. 없으면 logging 으로 fallback. */
        val serviceAccountPath: String = "",
    )
}
