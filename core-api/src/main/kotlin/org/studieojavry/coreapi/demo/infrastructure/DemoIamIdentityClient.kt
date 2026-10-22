package org.studieojavry.coreapi.demo.infrastructure

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.coreapi.errorcase.shared.config.IamApiProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer
import java.time.Duration

/**
 * iam-api `GET /internal/demo/identity` 호출 — 데모 워크스페이스/봇 ID 를 얻는다.
 *
 * 공용 [iamApiRestClient][org.studieojavry.coreapi.errorcase.shared.infrastructure.iam.IamApiRestClientConfig]
 * 는 **SecurityContext 가 있을 때만** 내부 토큰을 싣는다. 그런데 데모 리셋/부트스트랩은 인증 컨텍스트가
 * 없는 스케줄/기동 경로라 토큰이 안 실려 401 이 난다. 그래서 여기선 **시스템 내부 토큰**(subject=0)을
 * 직접 발급해 붙이는 전용 클라이언트를 둔다.
 */
@Component
class DemoIamIdentityClient(
    properties: IamApiProperties,
    private val internalTokenIssuer: InternalTokenIssuer,
) {
    private val log = KotlinLogging.logger {}

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                HttpClientSettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(2))
                    .withReadTimeout(Duration.ofSeconds(4)),
            ),
        )
        .build()

    fun fetchIdentity(): DemoIdentity? {
        val token = internalTokenIssuer.issue(subject = SYSTEM_SUBJECT, audience = AUDIENCE_IAM_API, roles = emptyList())
        return try {
            restClient.get()
                .uri("/internal/demo/identity")
                .header(InternalTokenAuthenticationFilter.HEADER_NAME, token)
                .retrieve()
                .body(DemoIdentity::class.java)
        } catch (e: Exception) {
            log.warn { "demo identity fetch failed (iam unreachable?): ${e.message}" }
            null
        }
    }

    data class DemoIdentity(
        val enabled: Boolean = false,
        val workspaceId: Long? = null,
        val botUserId: Long? = null,
        /** 시드 작성자 persona id 들. 구 iam(미제공) 호환 위해 기본 빈 목록 — 이 경우 봇으로 폴백. */
        val personaUserIds: List<Long> = emptyList(),
    )

    companion object {
        private const val AUDIENCE_IAM_API = "iam-api"
        private const val SYSTEM_SUBJECT = "0"
    }
}
