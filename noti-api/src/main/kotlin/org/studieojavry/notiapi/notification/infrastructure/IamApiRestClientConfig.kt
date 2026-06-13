package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import org.studieojavry.notiapi.shared.config.IamApiProperties

/**
 * iam-api 호출용 RestClient.
 *
 * noti-api 가 *자기 키* 로 internal JWT 발급(iss=noti-api, aud=iam-api) 후
 * X-Internal-Auth 헤더로 주입.
 *
 * Kafka consumer 등 *비-요청 컨텍스트* 에서도 호출되므로 SecurityContext 의 principal 에 의존하지 않고
 * sub = "system" + roles 비움 — iam-api 의 /internal/users/{id}/contact 는 sub 무관(서비스 인증만 검사).
 */
@Configuration
class IamApiRestClientConfig {

    @Bean
    fun iamApiRestClient(
        properties: IamApiProperties,
        internalTokenIssuer: InternalTokenIssuer,
    ): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestInterceptor { request, body, execution ->
                val token = internalTokenIssuer.issue(
                    subject = "system",
                    audience = AUDIENCE_IAM_API,
                    roles = emptyList(),
                )
                request.headers.set(InternalTokenAuthenticationFilter.HEADER_NAME, token)
                execution.execute(request, body)
            }
            .build()

    companion object {
        private const val AUDIENCE_IAM_API = "iam-api"
    }
}
