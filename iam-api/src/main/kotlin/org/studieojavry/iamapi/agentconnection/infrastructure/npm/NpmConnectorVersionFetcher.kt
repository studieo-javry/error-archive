package org.studieojavry.iamapi.agentconnection.infrastructure.npm

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.iamapi.agentconnection.application.port.ConnectorVersionSource
import org.studieojavry.iamapi.agentconnection.config.AgentConnectorProperties
import java.net.URI
import java.time.Duration

/**
 * npm 레지스트리에서 패키지 packument 의 `dist-tags.latest` 를 읽는다.
 * 비공개 패키지라 Authorization 헤더(Bearer npmToken)가 필요하다. 토큰 없음·실패 시 null.
 * 응답은 타입 DTO 대신 Map 으로 파싱해 Jackson 버전/어노테이션 의존을 피한다.
 */
@Component
class NpmConnectorVersionFetcher(
    private val properties: AgentConnectorProperties,
) : ConnectorVersionSource {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient =
        RestClient.builder()
            .requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofSeconds(5)) })
            .build()

    override fun fetchLatest(): String? {
        val token = properties.npmToken
        if (token.isNullOrBlank()) return null // 비공개 패키지 — 읽기 토큰 없으면 조회 불가
        // 미리 %2F 로 인코딩하면 RestClient 의 uri 템플릿이 '%' 를 또 인코딩해 %252F 가 되고 npm 이 405 를 반환한다.
        // java.net.URI 로 직접 만들어 넘기면(템플릿 처리 우회) 스코프 패키지의 '/' 가 그대로 전달돼 정상 조회된다.
        val uri = URI.create("${properties.npmRegistryUrl.trimEnd('/')}/${properties.packageName}")
        return try {
            val body =
                restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(Map::class.java)
            val distTags = body?.get("dist-tags") as? Map<*, *>
            (distTags?.get("latest") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (ex: Exception) {
            log.warn("npm 최신 버전 조회 실패: {}", ex.message)
            null
        }
    }
}
