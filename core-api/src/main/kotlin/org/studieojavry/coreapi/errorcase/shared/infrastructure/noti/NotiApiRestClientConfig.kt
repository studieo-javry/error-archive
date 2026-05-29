package org.studieojavry.coreapi.errorcase.shared.infrastructure.noti

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestClient
import org.studieojavry.coreapi.errorcase.shared.config.NotiApiProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer

/**
 * noti-api 호출용 RestClient.
 *
 * IamApiRestClient 와 동일 패턴 — core-api 가 *자기 키* 로 internal JWT 발급(iss=core-api, aud=noti-api)
 * 후 X-Internal-Auth 헤더로 주입.
 */
@Configuration
class NotiApiRestClientConfig {

    private val log = KotlinLogging.logger {}

    @Bean
    fun notiApiRestClient(
        properties: NotiApiProperties,
        internalTokenIssuer: InternalTokenIssuer,
    ): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor { request, body, execution ->
                currentUser()?.let { (userId, roles) ->
                    val token = internalTokenIssuer.issue(
                        subject = userId,
                        audience = AUDIENCE_NOTI_API,
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
        private const val AUDIENCE_NOTI_API = "noti-api"
        private const val ROLE_PREFIX = "ROLE_"
    }
}
