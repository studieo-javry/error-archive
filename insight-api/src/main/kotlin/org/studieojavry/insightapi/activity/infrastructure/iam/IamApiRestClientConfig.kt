package org.studieojavry.insightapi.activity.infrastructure.iam

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.studieojavry.insightapi.shared.config.IamApiProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer

/**
 * insight-api → iam-api 호출용 RestClient.
 *
 * Kafka consumer 컨텍스트(비-요청)에서도 호출되므로 SecurityContext principal 에 의존하지 않고
 * sub="system" 로 발급. iam-api 의 `/internal/users/{id}/preferences` 는 sub 무관(서비스 인증만 검사).
 */
@Configuration
class IamApiRestClientConfig {

    @Bean
    fun iamApiRestClient(
        properties: IamApiProperties,
        internalTokenIssuer: InternalTokenIssuer,
        // Boot 가 만든 빌더 — 추적 관측이 붙어 있어 호출 헤더로 traceId 가 넘어간다(같은 요청이 서비스를 건너도 한 추적).
        restClientBuilder: RestClient.Builder,
    ): RestClient =
        restClientBuilder
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
