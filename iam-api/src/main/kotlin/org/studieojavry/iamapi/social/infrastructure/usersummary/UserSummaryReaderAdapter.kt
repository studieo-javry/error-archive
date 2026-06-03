package org.studieojavry.iamapi.social.infrastructure.usersummary

import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.auth.infrastructure.jpa.UserJpaRepository
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort
import org.studieojavry.iamapi.social.application.port.UserSummaryReaderPort.UserSummary

@Component
class UserSummaryReaderAdapter(
    private val userJpa: UserJpaRepository
) : UserSummaryReaderPort {

    override fun existsActive(userId: Long): Boolean =
        userJpa.findById(userId).map { it.status == UserStatus.ACTIVE }.orElse(false)

    override fun findSummaries(userIds: List<Long>): List<UserSummary> {
        if (userIds.isEmpty()) return emptyList()
        return userJpa.findAllById(userIds).map {
            UserSummary(
                userId = it.id!!,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl
            )
        }
    }
}
