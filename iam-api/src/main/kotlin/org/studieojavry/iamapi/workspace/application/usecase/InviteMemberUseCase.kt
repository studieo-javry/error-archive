package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.shared.notification.application.port.NotificationPublisherPort
import org.studieojavry.iamapi.shared.util.HashUtils
import org.studieojavry.iamapi.workspace.application.command.InviteMemberCommand
import org.studieojavry.iamapi.workspace.application.port.InvitationEmailSenderPort
import org.studieojavry.iamapi.workspace.application.port.InvitationTokenGeneratorPort
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceInvitationRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort
import org.studieojavry.iamapi.workspace.application.port.WorkspaceRepositoryPort
import org.studieojavry.iamapi.workspace.config.WorkspaceProperties
import org.studieojavry.iamapi.workspace.domain.model.WorkspaceInvitation
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import java.time.Instant

@Service
class InviteMemberUseCase(
    private val workspaceRepository: WorkspaceRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val invitationRepository: WorkspaceInvitationRepositoryPort,
    private val tokenGenerator: InvitationTokenGeneratorPort,
    private val emailSender: InvitationEmailSenderPort,
    private val memberSummaryReader: MemberSummaryReaderPort,
    private val properties: WorkspaceProperties,
    private val notificationPublisher: NotificationPublisherPort,
) {

    @Transactional
    fun invoke(command: InviteMemberCommand): Result {
        val workspace = workspaceRepository.findById(command.workspaceId)
            ?: throw NoSuchElementException("workspace not found: ${command.workspaceId}")

        WorkspaceAccess.requireAdmin(memberRepository, command.workspaceId, command.actorUserId)

        if (command.role == WorkspaceRole.ADMIN) {
            throw IllegalArgumentException("ADMIN role cannot be granted via invitation; promote after join")
        }

        val ttl = (command.expiresInHours ?: properties.defaultInvitationTtlHours)
            .coerceIn(1, properties.maxInvitationTtlHours)
        val expiresAt = Instant.now().plusSeconds(ttl.toLong() * 3600)

        val rawToken = tokenGenerator.generate()
        val tokenHash = HashUtils.sha256(rawToken)

        // EMAIL type + 기존 활성 회원 인 경우 *userId 매핑* — in-app 알림 발화 대상.
        // 비등록 이메일은 userId 없어 발화 불가 (transactional invitation email 만 도달).
        var invitedUserId: Long? = null
        if (command.type == InvitationType.EMAIL) {
            require(!command.email.isNullOrBlank()) { "email is required for EMAIL invitation" }

            // 이미 멤버인 사용자에게 보내는 건 거부 (UX 가드)
            memberSummaryReader.findActiveByEmail(command.email)
                ?.let { existing ->
                    if (memberRepository.findByWorkspaceIdAndUserId(command.workspaceId, existing.userId) != null) {
                        throw IllegalStateException("user is already a member: ${existing.userId}")
                    }
                    invitedUserId = existing.userId
                }
        }

        val saved = invitationRepository.save(
            WorkspaceInvitation.create(
                workspaceId = command.workspaceId,
                invitedByUserId = command.actorUserId,
                type = command.type,
                email = command.email?.takeIf { command.type == InvitationType.EMAIL },
                tokenHash = tokenHash,
                role = command.role,
                expiresAt = expiresAt
            )
        )

        val acceptUrl = buildAcceptUrl(rawToken)

        if (command.type == InvitationType.EMAIL) {
            val inviter = memberSummaryReader.findSummaries(listOf(command.actorUserId)).firstOrNull()
            emailSender.send(
                toEmail = command.email!!,
                workspaceName = workspace.name.value,
                invitedByDisplayName = inviter?.displayName ?: "iam",
                role = command.role,
                acceptUrl = acceptUrl
            )
        }

        // in-app 알림 — 등록된 사용자에게만 (userId 매핑 가능 시). email 채널은 기존 invitation email 이 책임.
        invitedUserId?.let { uid ->
            notificationPublisher.publishWorkspaceInvitation(
                NotificationPublisherPort.WorkspaceInvitationEvent(
                    recipientUserId = uid,
                    workspaceId = command.workspaceId,
                    workspaceName = workspace.name.value,
                    invitationId = saved.id!!,
                    invitedByUserId = command.actorUserId,
                )
            )
        }

        return Result(
            invitationId = saved.id!!,
            type = saved.type,
            role = saved.role,
            expiresAt = saved.expiresAt,
            // LINK 타입이면 본문에서 노출, EMAIL 타입이면 노출하지 않음(이메일에만)
            inviteToken = if (command.type == InvitationType.LINK) rawToken else null,
            inviteUrl = if (command.type == InvitationType.LINK) acceptUrl else null
        )
    }

    private fun buildAcceptUrl(token: String): String {
        val base = properties.inviteBaseUrl.trimEnd('/')
        return "$base/$token"
    }

    data class Result(
        val invitationId: Long,
        val type: InvitationType,
        val role: WorkspaceRole,
        val expiresAt: Instant,
        val inviteToken: String?,
        val inviteUrl: String?
    )
}