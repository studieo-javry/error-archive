package org.studieojavry.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import javax.crypto.spec.SecretKeySpec

@Configuration
class JwtDecoderConfig {

    @Bean
    fun jwtDecoder(jwtProperties: JwtProperties): JwtDecoder {
        val secretKey = SecretKeySpec(
            jwtProperties.secret.toByteArray(Charsets.UTF_8),
            "HmacSHA256"
        )
        val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

        val issuerValidator: OAuth2TokenValidator<Jwt> =
            JwtClaimValidator("iss") { iss: String? -> iss == jwtProperties.issuer }
        val audienceValidator: OAuth2TokenValidator<Jwt> =
            JwtClaimValidator<List<String>>("aud") { aud -> aud != null && jwtProperties.audience in aud }
        // typ=access (일반 API) + typ=sse (EventSource 가 URL query 로 첨부하는 단명 ticket) 둘 다 허용.
        // SseAwareBearerTokenResolver 가 typ=sse 토큰을 *SSE path 의 query 로만* 추출하므로
        // 다른 endpoint 에 흘러가도 resolver 가 받지 않아 사실상 path 별 격리.
        val typeValidator: OAuth2TokenValidator<Jwt> =
            JwtClaimValidator("typ") { typ: String? -> typ == "access" || typ == "sse" }

        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                issuerValidator,
                audienceValidator,
                typeValidator
            )
        )
        return decoder
    }
}
