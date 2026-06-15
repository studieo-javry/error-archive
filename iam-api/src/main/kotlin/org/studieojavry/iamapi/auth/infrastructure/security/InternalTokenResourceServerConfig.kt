package org.studieojavry.iamapi.auth.infrastructure.security

import com.nimbusds.jwt.SignedJWT
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.studieojavry.internalauth.InternalAuthProperties
import org.studieojavry.internalauth.InternalTokenAuthenticationFilter
import org.studieojavry.internalauth.PemKeyParser

/**
 * iam-api 의 resource server 가 검증하는 토큰을 사용자 JWT 가 아니라
 * **게이트웨이/core-api 가 발급한 internal JWT** 로 전환한다.
 *
 * 핵심은 principal 타입을 그대로 Jwt 로 유지하는 것 — 모든 컨트롤러가
 * `@AuthenticationPrincipal Jwt` 로 `jwt.subject` 를 읽으므로 컨트롤러는 무변경.
 *
 *  - bearerTokenResolver: Authorization 대신 X-Internal-Auth 헤더에서 토큰을 꺼낸다.
 *  - jwtDecoder: iss 클레임으로 발급자를 구분해 해당 공개키로 RS256 서명 검증 + aud/exp 검증.
 *
 * token relay 를 제거했으므로 사용자 JWT 는 더 이상 iam-api 까지 오지 않는다.
 */
@Configuration
class InternalTokenResourceServerConfig {

    @Bean
    fun internalBearerTokenResolver(): BearerTokenResolver =
        BearerTokenResolver { request -> request.getHeader(InternalTokenAuthenticationFilter.HEADER_NAME) }

    @Bean
    fun internalJwtDecoder(properties: InternalAuthProperties): JwtDecoder {
        val verifier = requireNotNull(properties.verifier) { "internal-auth.verifier is required" }
        val decodersByIssuer = verifier.knownIssuers.mapValues { (issuer, pem) ->
            val decoder = NimbusJwtDecoder.withPublicKey(PemKeyParser.parsePublicKey(pem))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
            val validators: OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                JwtClaimValidator<String?>("iss") { it == issuer },
                JwtClaimValidator<List<String>?>("aud") { aud -> aud != null && verifier.audience in aud },
            )
            decoder.setJwtValidator(validators)
            decoder as JwtDecoder
        }
        return MultiIssuerJwtDecoder(decodersByIssuer)
    }
}

/**
 * iss 클레임으로 발급자별 디코더를 라우팅한다. iss 는 서명 검증 전에 unverified 로 읽고,
 * 실제 검증(서명/aud/exp)은 선택된 디코더의 decode() 가 수행한다.
 */
class MultiIssuerJwtDecoder(
    private val decodersByIssuer: Map<String, JwtDecoder>,
) : JwtDecoder {

    override fun decode(token: String): Jwt {
        val issuer = runCatching { SignedJWT.parse(token).jwtClaimsSet.issuer }.getOrNull()
            ?: throw BadJwtException("missing or unparseable iss claim")
        val decoder = decodersByIssuer[issuer]
            ?: throw BadJwtException("unknown issuer: $issuer")
        return decoder.decode(token)
    }
}
