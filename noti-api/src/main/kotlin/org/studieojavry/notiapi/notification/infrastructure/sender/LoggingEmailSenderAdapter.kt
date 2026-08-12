package org.studieojavry.notiapi.notification.infrastructure.sender

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.sender.EmailSenderPort

/** default 안전 구현 — 콘솔에 찍기만. */
@Component
@ConditionalOnProperty(prefix = "noti.email", name = ["provider"], havingValue = "logging", matchIfMissing = true)
class LoggingEmailSenderAdapter : EmailSenderPort {
    private val log = KotlinLogging.logger {}
    override fun send(message: EmailSenderPort.EmailMessage) {
        log.info { "[EMAIL] to=${message.to} subject=${message.subject}\n--- text ---\n${message.textBody}" }
    }
}
