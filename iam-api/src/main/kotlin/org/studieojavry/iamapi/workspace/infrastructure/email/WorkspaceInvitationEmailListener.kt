package org.studieojavry.iamapi.workspace.infrastructure.email

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.studieojavry.iamapi.workspace.application.event.WorkspaceInvitationEmailRequested
import org.studieojavry.iamapi.workspace.application.port.InvitationEmailSenderPort

/**
 * 초대 메일 발송 리스너.
 *
 * - `AFTER_COMMIT`: 초대 트랜잭션이 **커밋된 뒤에만** 실행 → 롤백된 초대에 대해 메일이 나가지 않고,
 *   메일 발송 실패가 초대 저장을 되돌리지 않는다.
 * - `@Async`: 별도 스레드에서 발송 → HTTP 응답 스레드/DB 커넥션을 SMTP 왕복 동안 붙잡지 않는다.
 *
 * 커밋 이후 비동기라 발송 실패는 호출자에게 전달되지 않으므로, 여기서 **ERROR 로그**로 남긴다.
 * (초대 row 는 이미 저장돼 있으므로 관리자가 재발송 가능. 자동 재시도까지 필요해지면 outbox 로 승격.)
 */
@Component
class WorkspaceInvitationEmailListener(
    private val emailSender: InvitationEmailSenderPort,
) {

    private val log = KotlinLogging.logger {}

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onInvitationEmailRequested(event: WorkspaceInvitationEmailRequested) {
        try {
            emailSender.send(
                toEmail = event.toEmail,
                workspaceName = event.workspaceName,
                invitedByDisplayName = event.invitedByDisplayName,
                role = event.role,
                acceptUrl = event.acceptUrl,
                expiresAt = event.expiresAt,
            )
        } catch (e: Exception) {
            // 커밋 후 비동기이므로 재던져도 호출자에게 못 감. 발송 실패를 반드시 관측 가능하게 남긴다.
            log.error(e) {
                "[invitation-email] async send failed to=${event.toEmail} workspace=${event.workspaceName} — 초대는 저장됨, 재발송 필요"
            }
        }
    }
}
