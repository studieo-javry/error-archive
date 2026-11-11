package org.studieojavry.gateway.filter

import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.studieojavry.gateway.config.DownstreamProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.InternalTokenIssuer

/**
 * agent opaque 토큰을 iam-api 의 내부 introspection 으로 매 요청 검증한다.
 * (게이트웨이는 토큰 저장소를 갖지 않으므로 소유자 iam 에 위임 — 폐기/만료는 iam 이 매 요청 검사.)
 *
 * 이 호출 자체는 서비스-투-서비스라, 게이트웨이가 sub=0(시스템) 내부 JWT(aud=iam-api)로 인증한다.
 */
@Component
class AgentIntrospectionClient(
    downstream: DownstreamProperties,
    private val internalTokenIssuer: InternalTokenIssuer,
) {
    private val restClient: RestClient = RestClient.builder().baseUrl(downstream.iamApi).build()

    /**
     * @return 검증된 principal, 또는 토큰이 invalid/revoked/expired(iam 이 401)면 null.
     * @throws RuntimeException iam 도달 실패/5xx — 호출 필터가 503 으로 매핑한다(토큰을 통과시키지 않음).
     */
    fun introspect(rawToken: String, client: ClientDescriptor? = null): AgentPrincipal? {
        val internalToken = internalTokenIssuer.issue(
            subject = SYSTEM_SUBJECT,
            audience = HeaderInjectionFilter.AUDIENCE_IAM_API,
            roles = emptyList(),
        )
        return try {
            restClient.post()
                .uri("/internal/agent-connections/introspect")
                .header(InternalTokenAuthenticationFilter.HEADER_NAME, internalToken)
                .body(
                    IntrospectRequest(
                        token = rawToken,
                        clientKind = client?.kind,
                        clientVersion = client?.version,
                        installId = client?.installId,
                        installLabel = client?.installLabel,
                    ),
                )
                .retrieve()
                .body(AgentPrincipal::class.java)
        } catch (_: HttpClientErrorException.Unauthorized) {
            null
        }
    }

    /** 커넥터가 헤더로 신고한 설치 메타(표시용, 선택). 게이트웨이는 그대로 전달만 한다(검증 X). */
    data class ClientDescriptor(
        val kind: String?,
        val version: String?,
        val installId: String?,
        val installLabel: String?,
    )

    data class IntrospectRequest(
        val token: String,
        val clientKind: String? = null,
        val clientVersion: String? = null,
        val installId: String? = null,
        val installLabel: String? = null,
    )

    /** iam introspection 응답과 1:1 (scopes 는 wire 문자열). */
    data class AgentPrincipal(
        val userId: Long = 0,
        val connectionId: String = "",
        val scopes: List<String> = emptyList(),
    )

    companion object {
        private const val SYSTEM_SUBJECT = "0"
    }
}
