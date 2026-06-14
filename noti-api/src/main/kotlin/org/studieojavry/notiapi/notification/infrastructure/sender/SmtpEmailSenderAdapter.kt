package org.studieojavry.notiapi.notification.infrastructure.sender

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.sender.EmailSenderPort

/**
 * 운영 SMTP 어댑터. 로컬에선 mailhog(1026) 사용 — `noti-mailhog` 컨테이너.
 * 발송 실패는 *상위로 throw* — 호출자(UseCase) 가 try/catch 로 *알림 누락 허용*.
 */
@Component
@ConditionalOnProperty(prefix = "noti.email", name = ["provider"], havingValue = "smtp")
class SmtpEmailSenderAdapter(
    private val mailSender: JavaMailSender,
    private val properties: EmailProperties,
) : EmailSenderPort {

    private val log = KotlinLogging.logger {}

    override fun send(message: EmailSenderPort.EmailMessage) {
        val mime: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mime, true, "UTF-8")
        helper.setFrom(InternetAddress(properties.fromAddress, properties.fromName, "UTF-8"))
        helper.setTo(message.to)
        helper.setSubject("${properties.subjectPrefix} ${message.subject}")
        helper.setText(message.textBody, message.htmlBody)
        mailSender.send(mime)
        log.debug { "[SMTP] sent to=${message.to} subject=${message.subject}" }
    }
}
