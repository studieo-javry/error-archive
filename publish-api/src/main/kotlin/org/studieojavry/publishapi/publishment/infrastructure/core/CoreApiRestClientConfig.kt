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
        // Boot 가 만든 빌더 — 추적 관측이 붙어 있어 호출 헤더로 traceId 가 넘어간다(같은 요청이 서비스를 건너도 한 추적).
        restClientBuilder: RestClient.Builder,
    ): RestClient =
        restClientBuilder
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
