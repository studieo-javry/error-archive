package org.studieojavry.iamapi.auth.application.command

import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

data class StartOAuthFlowCommand(
    val provider: SocialProvider,
    val redirectUri: String
)