package org.studieojavry.iamapi.workspace.infrastructure.email

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.workspace.application.port.InvitationEmailSenderPort
import org.studieojavry.iamapi.workspace.config.InvitationEmailProperties
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 실제 SMTP 발송 어댑터.
 *
 * - `iam.email.provider=smtp` 일 때만 빈 등록.
 * - HTML 본문은 `classpath:email/invitation.html` 로드 후 `{{key}}` 치환.
 * - `expiresAt` 은 port 시그니처에 없으므로 별도 오버로드 없이 본문에선 "초대 링크는 일정 시간 후 만료" 만 표시할 수도 있으나,
 *   템플릿이 expiresAt 자리를 요구하므로 어댑터 내부에서 "verified soon" 디폴트 + 추후 port 확장 시 실제 값으로 교체 가능.
 */
@Component
@ConditionalOnProperty(name = ["iam.email.provider"], havingValue = "smtp")
class SmtpInvitationEmailSenderAdapter(
    private val mailSender: JavaMailSender,
    private val properties: InvitationEmailProperties
) : InvitationEmailSenderPort {

    private val log = KotlinLogging.logger {}
    private val template: String by lazy { loadTemplate() }
    private val expiryFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
        .withZone(ZoneId.of("Asia/Seoul"))

    override fun send(
        toEmail: String,
        workspaceName: String,
        invitedByDisplayName: String,
        role: WorkspaceRole,
        acceptUrl: String
    ) {
        val body = renderBody(
            workspaceName = workspaceName,
            invitedByDisplayName = invitedByDisplayName,
            role = role,
            acceptUrl = acceptUrl
        )
        val subject = "${properties.subjectPrefix} $workspaceName 워크스페이스 초대"

        val mime = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name())
        helper.setFrom("${properties.fromName} <${properties.fromAddress}>")
        helper.setTo(toEmail)
        helper.setSubject(subject)
        helper.setText(body, true)

        try {
            mailSender.send(mime)
            log.info { "[invitation-email] sent to=$toEmail workspace=$workspaceName role=$role" }
        } catch (e: MailException) {
            log.error(e) { "[invitation-email] send failed to=$toEmail workspace=$workspaceName" }
            throw e
        }
    }

    private fun renderBody(
        workspaceName: String,
        invitedByDisplayName: String,
        role: WorkspaceRole,
        acceptUrl: String
    ): String = template
        .replace("{{workspaceName}}", escapeHtml(workspaceName))
        .replace("{{invitedByDisplayName}}", escapeHtml(invitedByDisplayName))
        .replace("{{roleLabel}}", role.displayLabel())
        // expiresAt 은 현재 port 시그니처에 없어 어댑터 시점에선 모름. 임시로 "관리자 안내 참고" 로 표기.
        // 추후 InvitationEmailSenderPort 에 expiresAt 인자 추가하면 실제 값으로 치환.
        .replace("{{expiresAt}}", "관리자 안내 참고")
        .replace("{{acceptUrl}}", acceptUrl)

    private fun WorkspaceRole.displayLabel(): String = when (this) {
        WorkspaceRole.ADMIN -> "관리자"
        WorkspaceRole.WRITE -> "쓰기"
        WorkspaceRole.READ -> "읽기"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun loadTemplate(): String =
        ClassPathResource("email/invitation.html").inputStream
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}
