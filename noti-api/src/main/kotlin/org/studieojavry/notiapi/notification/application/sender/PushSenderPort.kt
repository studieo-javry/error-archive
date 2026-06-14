package org.studieojavry.notiapi.notification.application.sender

/**
 * Push 발송 추상화. 구현체:
 *  - `FcmPushSenderAdapter` (`noti.push.provider=fcm`) — Firebase HTTP v1 API
 *  - `LoggingPushSenderAdapter` (`noti.push.provider=logging`) — 콘솔만 (default 로컬)
 *
 * tokens 가 비어 있으면 no-op.
 */
interface PushSenderPort {
    fun send(message: PushMessage)

    data class PushMessage(
        val tokens: List<String>,
        val title: String,
        val body: String,
        /** type-specific data (errorCaseId/commentId 등) — FCM data payload 로 그대로. */
        val data: Map<String, String> = emptyMap(),
    )
}
