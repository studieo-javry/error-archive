package org.studieojavry.iamapi.workspace.domain.model.vo

enum class InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED;

    fun isTerminal(): Boolean = this != PENDING
}
