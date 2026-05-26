package org.studieojavry.iamapi.workspace.infrastructure.membersummary

import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.auth.infrastructure.jpa.UserJpaRepository
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort
import org.studieojavry.iamapi.workspace.application.port.MemberSummaryReaderPort.MemberSummary

@Component
class MemberSummaryReaderAdapter(
    private val userJpa: UserJpaRepository
) : MemberSummaryReaderPort {

    override fun existsActive(userId: Long): Boolean =
        userJpa.findById(userId).map { it.status == UserStatus.ACTIVE }.orElse(false)

    override fun findSummaries(userIds: Collection<Long>): List<MemberSummary> {
        if (userIds.isEmpty()) return emptyList()
        return userJpa.findAllById(userIds).map {
            MemberSummary(
                userId = it.id!!,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl,
                email = it.email
            )
        }
    }

    override fun findActiveByEmail(email: String): MemberSummary? {
        val entity = userJpa.findFirstByEmailIgnoreCase(email) ?: return null
        if (entity.status != UserStatus.ACTIVE) return null
        return MemberSummary(entity.id!!, entity.displayName, entity.avatarUrl, entity.email)
    }
}