package org.studieojavry.iamapi.auth.application.command

import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.ProviderCredential
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

data class SocialLoginCommand(
    val provider: SocialProvider,
    val credential: ProviderCredential,
    val rememberMe: Boolean,
    val deviceLabel: String? = null,
    val userAgent: String? = null,
    val ipAddress: String? = null,
)
