package org.studieojavry.internalauth

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean

/**
 * shared-internal-auth 가 classpath 에 있으면 자동 구성된다.
 *
 *  - internal-auth.issuer.name   설정 시 → InternalTokenIssuer 빈 등록 (발급자)
 *  - internal-auth.verifier.audience 설정 시 → InternalTokenAuthenticationFilter 빈 등록 (검증자)
 *
 * 필터는 빈으로만 제공한다. 각 서비스가 자신의 SecurityFilterChain 에
 * addFilterBefore(...) 로 직접 끼워 넣어야 한다 (서비스마다 체인 구성이 다르므로).
 */
@AutoConfiguration
@EnableConfigurationProperties(InternalAuthProperties::class)
class InternalAuthAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "internal-auth.issuer", name = ["name"])
    fun internalTokenIssuer(properties: InternalAuthProperties): InternalTokenIssuer {
        val issuer = requireNotNull(properties.issuer) { "internal-auth.issuer is required" }
        return InternalTokenIssuer(
            issuerName = issuer.name,
            privateKey = PemKeyParser.parsePrivateKey(issuer.privateKeyPem),
            keyId = issuer.keyId,
            ttlSeconds = properties.ttlSeconds,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "internal-auth.verifier", name = ["audience"])
    fun internalTokenAuthenticationFilter(properties: InternalAuthProperties): InternalTokenAuthenticationFilter {
        val verifier = requireNotNull(properties.verifier) { "internal-auth.verifier is required" }
        val knownIssuers = verifier.knownIssuers.mapValues { (_, pem) -> PemKeyParser.parsePublicKey(pem) }
        return InternalTokenAuthenticationFilter(
            expectedAudience = verifier.audience,
            knownIssuers = knownIssuers,
            clockSkewSeconds = properties.clockSkewSeconds,
        )
    }

    /**
     * Spring Boot 는 Filter 타입 빈을 서블릿 필터 체인에 자동 등록한다. 그러면 이 필터가
     * Spring Security 체인 밖(앞)에서 한 번 더 돌게 된다. 우리는 각 서비스가
     * addFilterBefore(...) 로 보안 체인에 명시적으로 끼우거나(core-api), 아예 안 쓰는(iam-api)
     * 방식을 쓰므로 자동 등록을 비활성화한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "internal-auth.verifier", name = ["audience"])
    fun internalTokenFilterRegistration(
        filter: InternalTokenAuthenticationFilter,
    ): FilterRegistrationBean<InternalTokenAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }
}
