package org.studieojavry.iamapi.workspace.infrastructure.email

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.workspace.application.port.InvitationEmailSenderPort
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

/**
 * SMTP 미연동 fallback 어댑터. 실제 발송 대신 로그만 남긴다.
 * `iam.email.provider=logging` (또는 미지정) 일 때 활성화. `smtp` 어댑터와는 상호 배제.
 */
@Component
@ConditionalOnProperty(name = ["iam.email.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingInvitationEmailSenderAdapter : InvitationEmailSenderPort {

    private val log = LoggerFactory.getLogger(LoggingInvitationEmailSenderAdapter::class.java)

    override fun send(
        toEmail: String,
        workspaceName: String,
        invitedByDisplayName: String,
        role: WorkspaceRole,
        acceptUrl: String
    ) {
        log.info(
            "[invitation-email] to={} workspace={} invitedBy={} role={} acceptUrl={}",
            toEmail, workspaceName, invitedByDisplayName, role, acceptUrl
        )
    }
}