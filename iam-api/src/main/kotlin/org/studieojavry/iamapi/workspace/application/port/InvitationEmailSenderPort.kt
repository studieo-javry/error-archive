package org.studieojavry.iamapi.workspace.application.port

import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

interface InvitationEmailSenderPort {
    fun send(
        toEmail: String,
        workspaceName: String,
        invitedByDisplayName: String,
        role: WorkspaceRole,
        acceptUrl: String
    )
}