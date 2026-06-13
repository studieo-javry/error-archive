package org.studieojavry.notiapi.notification.infrastructure.sender

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.notiapi.notification.application.sender.PushSenderPort

/**
 * Firebase Cloud Messaging HTTP v1 어댑터.
 *
 * - endpoint: `https://fcm.googleapis.com/v1/projects/{projectId}/messages:send`
 * - 인증: 서비스 계정 키 → OAuth2 access token (별도 jose 라이브러리 또는 google-auth-library 권장)
 *
 * **본 PR 은 *어댑터 골격* 만**. 서비스 계정 인증 + access token 캐시는 운영 도입 시점에 채움.
 * 키가 없으면 *조용히 noop* — 부팅은 정상.
 */
@Component
@ConditionalOnProperty(prefix = "noti.push", name = ["provider"], havingValue = "fcm")
class FcmPushSenderAdapter(
    private val properties: PushProperties,
) : PushSenderPort {

    private val log = KotlinLogging.logger {}
    private val restClient: RestClient = RestClient.builder()
        .baseUrl("https://fcm.googleapis.com")
        .build()

    override fun send(message: PushSenderPort.PushMessage) {
        if (message.tokens.isEmpty()) return
        if (properties.fcm.projectId.isBlank() || properties.fcm.serviceAccountPath.isBlank()) {
            log.warn { "[FCM] not configured (projectId/serviceAccountPath empty) — dropping ${message.tokens.size} tokens" }
            return
        }
        // TODO: 서비스 계정 OAuth2 access token 발급 → Bearer 헤더로 호출
        //       token 별로 messages:send 단건 호출 또는 batchSend 사용.
        //       실제 발송 코드는 운영 도입 단계에 추가 (google-auth-library-oauth2-http 의존성 등).
        log.info { "[FCM-stub] projectId=${properties.fcm.projectId} tokens=${message.tokens.size} title=${message.title}" }
    }
}