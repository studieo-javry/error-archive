package org.studieojavry.publishapi.publishment.infrastructure.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestClient
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import org.studieojavry.publishapi.shared.config.CoreApiProperties

/**
 * core-api 호출용 RestClient.
 *
 * publish-api 가 자기 키로 internal JWT 발급(iss=publish-api, aud=core-api).
 * caller userId 는 *요청 컨텍스트의 사용자* — core-api 가 case owner 와 비교해 권한 검사.
 */
@Configuration
class CoreApiRestClientConfig {

    @Bean
    fun coreApiRestClient(
        properties: CoreApiProperties,
        internalTokenIssuer: InternalTokenIssuer,
    ): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor { request, body, execution ->
                currentUser()?.let { (userId, roles) ->
                    val token = internalTokenIssuer.issue(
                        subject = userId,
                        audience = AUDIENCE_CORE_API,
                        roles = roles,
                    )
                    request.headers.set(InternalTokenAuthenticationFilter.HEADER_NAME, token)
                }
                execution.execute(request, body)
            }
            .build()

    private fun currentUser(): Pair<String, List<String>>? {
        val auth = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated } ?: return null
        val userId = auth.name ?: return null
        val roles = auth.authorities.mapNotNull { it.authority?.removePrefix(ROLE_PREFIX) }
        return userId to roles
    }

    companion object {
        private const val AUDIENCE_CORE_API = "core-api"
        private const val ROLE_PREFIX = "ROLE_"
    }
}
