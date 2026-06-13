package org.studieojavry.notiapi.notification.infrastructure.sender

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.sender.PushSenderPort

/** default 안전 구현 — 콘솔에만 찍는다. FCM 키 없이 로컬 e2e 가능. */
@Component
@ConditionalOnProperty(prefix = "noti.push", name = ["provider"], havingValue = "logging", matchIfMissing = true)
class LoggingPushSenderAdapter : PushSenderPort {
    private val log = KotlinLogging.logger {}
    override fun send(message: PushSenderPort.PushMessage) {
        if (message.tokens.isEmpty()) return
        log.info { "[PUSH] tokens=${message.tokens.size} title=${message.title} body=${message.body} data=${message.data}" }
    }
}