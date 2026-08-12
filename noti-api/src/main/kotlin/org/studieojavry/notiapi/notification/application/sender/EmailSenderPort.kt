package org.studieojavry.notiapi.notification.application.sender

/**
 * Email 발송 추상화. 구현체:
 *  - `SmtpEmailSenderAdapter` (`noti.email.provider=smtp`) — JavaMailSender / 로컬 mailhog
 *  - `LoggingEmailSenderAdapter` (`noti.email.provider=logging`) — 콘솔만
 *
 * Sender 는 *발송* 만 책임. 사용자 설정 확인은 호출자(UseCase) 의 책임.
 */
interface EmailSenderPort {
    fun send(message: EmailMessage)

    data class EmailMessage(
        val to: String,
        val subject: String,
        val htmlBody: String,
        val textBody: String,
    )
}
