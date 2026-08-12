package org.studieojavry.iamapi.workspace.application.event

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

/**
 * 초대 메일 발송 요청 이벤트.
 *
 * InviteMemberUseCase 가 트랜잭션 *안에서* publish 하지만, 실제 발송은
 * [org.studieojavry.iamapi.workspace.infrastructure.email.WorkspaceInvitationEmailListener]
 * 가 트랜잭션 **커밋 이후**(AFTER_COMMIT)에 비동기로 처리한다.
 *
 * → DB 트랜잭션/커넥션을 SMTP I/O 동안 붙잡지 않으며, 발송 실패가 초대 저장을 롤백하지 않는다.
 */
data class WorkspaceInvitationEmailRequested(
    val toEmail: String,
    val workspaceName: String,
    val invitedByDisplayName: String,
    val role: WorkspaceRole,
    val acceptUrl: String,
    val expiresAt: Instant,
)
