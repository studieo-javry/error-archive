package org.studieojavry.coreapi.errorcase.shared.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestClient
import org.studieojavry.coreapi.errorcase.shared.config.IamApiProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import java.time.Duration

/**
 * iam-api 호출용 RestClient.
 *
 * 과거엔 게이트웨이가 보낸 사용자 Authorization(JWT) / X-User-Id 를 그대로 relay 했다 (token relay).
 * 이는 confused-deputy 위험이 있고, downstream 이 "누가 호출했는지" 를 알 수 없었다.
 *
 * 이제 core-api 가 **자기 키로** 새 internal JWT 를 발급해 X-Internal-Auth 로 보낸다:
 *   iss = core-api   (iam-api 는 이걸로 "core-api 를 거쳐 들어온 호출" 을 검증)
 *   aud = iam-api
 *   sub = 원 사용자 ID (게이트웨이 토큰을 검증해 SecurityContext 에 담긴 값)
 *
 * 인증되지 않은 컨텍스트에서 호출되면 토큰을 싣지 않는다 (iam-api 가 401 로 차단).
 */
@Configuration
class IamApiRestClientConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun iamApiRestClient(
        properties: IamApiProperties,
        internalTokenIssuer: InternalTokenIssuer,
    ): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            // connect/read timeout — iam-api 가 느려지거나 멈춰도 홈 대시보드 등 호출 스레드가
            // 무한정 매달리지 않도록 소켓 레벨 하드 캡. gateway 10s 보다 짧게.
            .requestFactory(
                ClientHttpRequestFactoryBuilder.detect().build(
                    HttpClientSettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(4))
                )
            )
            .requestInterceptor { request, body, execution ->
                logger.debug { "[filter] thread=${Thread.currentThread().name}" }
                logger.debug { "[before currentUser] auth=${SecurityContextHolder.getContext().authentication}" }
                val user = currentUser()
                logger.debug { "IamApiRestClient: user=$user" }
                user?.let { (userId, roles) ->
                    val token = internalTokenIssuer.issue(
                        subject = userId,
                        audience = AUDIENCE_IAM_API,
                        roles = roles,
                    )
                    request.headers.set(InternalTokenAuthenticationFilter.HEADER_NAME, token)
                }
                execution.execute(request, body)
            }
            .build()

    private fun currentUser(): Pair<String, List<String>>? {
        val authentication = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated }
            ?: return null
        val userId = authentication.name ?: return null
        val roles = authentication.authorities
            .mapNotNull { it.authority?.removePrefix(ROLE_PREFIX) }
        return userId to roles
    }

    companion object {
        private const val AUDIENCE_IAM_API = "iam-api"
        private const val ROLE_PREFIX = "ROLE_"
    }
}
