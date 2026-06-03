package org.studieojavry.iamapi.auth.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.studieojavry.iamapi.auth.application.command.StartOAuthFlowCommand
import org.studieojavry.iamapi.auth.application.port.AuthorizationUrlBuilderPort
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort

@Service
class StartOAuthFlowUseCase(
    private val authorizationUrlBuilders: List<AuthorizationUrlBuilderPort>,
    private val secureRandom: SecureRandomPort
) {

    private val log = KotlinLogging.logger {}

    fun invoke(command: StartOAuthFlowCommand): Result {
        val builder = authorizationUrlBuilders.firstOrNull { it.supports(command.provider) }
            ?: throw IllegalArgumentException("provider not supported: ${command.provider}")
        log.info { "StartOAuthFlowUseCase $builder" }
        val state = secureRandom.generateUrlSafeToken(32)
        log.info { "state $state" }
        val url = builder.build(state = state, redirectUri = command.redirectUri)
        log.info { "redirectUri $url" }
        return Result(authorizationUrl = url, state = state)
    }

    data class Result(val authorizationUrl: String, val state: String)
}
