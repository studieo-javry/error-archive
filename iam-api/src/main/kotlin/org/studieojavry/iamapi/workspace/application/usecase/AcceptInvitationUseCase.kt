package org.studieojavry.iamapi.workspace.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.shared.util.HashUtils
import org.studieojavry.iamapi.workspace.application.command.AcceptInvitationCommand
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceMember
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

/**
 * 초대 수락 — **토큰=권한(token-as-capability)** 모델.
 *
 * 초대 토큰은 그 초대 메일함/링크에만 전달되므로, 유효·미만료·미사용 토큰을 가진 **로그인된 사용자**면 수락을 허용한다.
 * 과거의 "계정 이메일 == 초대 이메일" 강제 일치는 제거했다 — GitHub OAuth 가입 시 계정 이메일이 초대 이메일과
 * 다르거나(null) 할 수 있어 신규 가입자를 막았고, 토큰 소지 자체가 이미 메일함 통제의 증거이기 때문.
 *
 * 안전장치: 추측 불가 토큰 + 짧은 TTL + **1회용 원자 claim**(동시/재사용 차단). 전달(forward) 오용은
 * TTL·1회용·관리자의 멤버 제거로 완화(Slack/Notion/GitHub 초대 링크와 동일한 모델).
 */
@Service
class AcceptInvitationUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val invitationRepository: WorkspaceInvitationRepositoryPort,
    private val memberSummaryReader: MemberSummaryReaderPort
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun invoke(command: AcceptInvitationCommand): Result {
        if (!memberSummaryReader.existsActive(command.acceptingUserId)) {
            throw NoSuchElementException("user not found: ${command.acceptingUserId}")
        }

        val tokenHash = HashUtils.sha256(command.token)
        val invitation = invitationRepository.findByTokenHash(tokenHash)
            ?: throw InvalidInvitationException("invitation not recognized")

        when (invitation.type) {
            // EMAIL = 특정인 1회용: 원자 claim(PENDING+미만료 → ACCEPTED). 동시/재사용 시 0행 → 거부.
            InvitationType.EMAIL -> {
                val claimed = invitationRepository.claimForAcceptance(tokenHash, command.acceptingUserId, Instant.now())
                if (!claimed) {
                    throw InvalidInvitationException("invitation is not usable (already used, revoked, or expired)")
                }
            }
            // LINK = 공유 다회용: 토큰을 소비하지 않는다. 만료/취소 전(PENDING+미만료)까지 누구나 합류.
            // 통제는 만료(expiresAt) + 취소(revoke→REVOKED) + 멤버 unique 제약(중복 합류 방지)으로.
            InvitationType.LINK -> {
                if (!invitation.isPending()) {
                    throw InvalidInvitationException("invite link is not usable (revoked or expired)")
                }
            }
        }

        val workspace = workspaceRepository.findById(invitation.workspaceId)
            ?: throw IllegalStateException("workspace gone: ${invitation.workspaceId}")

        val existing = memberRepository.findByWorkspaceIdAndUserId(workspace.id!!, command.acceptingUserId)
        if (existing == null) {
            // 초대로는 ADMIN을 부여하지 않음 (도메인 init에서도 막힘)
            memberRepository.save(
                WorkspaceMember.join(
                    workspaceId = workspace.id,
                    userId = command.acceptingUserId,
                    role = invitation.role.takeIf { it != WorkspaceRole.ADMIN } ?: WorkspaceRole.READ
                )
            )
        }
        invitation.accept(command.acceptingUserId)
        invitationRepository.save(invitation)

        log.info {
            "[invitation] accepted invitationId=${invitation.id} workspaceId=${workspace.id} " +
                "by user=${command.acceptingUserId} (invitedEmail=${invitation.email})"
        }

        return Result(
            workspaceId = workspace.id,
            workspaceName = workspace.name.value,
            role = invitation.role
        )
    }

    data class Result(
        val workspaceId: Long,
        val workspaceName: String,
        val role: WorkspaceRole
    )

    class InvalidInvitationException(message: String) : RuntimeException(message)
}
