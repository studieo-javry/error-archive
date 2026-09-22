package org.studieojavry.internalauth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 내부 인증 설정.
 *
 *  - issuer 블록이 있으면 이 서비스는 internal token 을 **발급**한다 (gateway, core-api).
 *  - verifier 블록이 있으면 이 서비스는 internal token 을 **검증**한다 (core-api, iam-api).
 *  한 서비스가 둘 다 가질 수 있다 (core-api: gateway 토큰 검증 + iam-api 호출용 발급).
 *
 * @property ttlSeconds      발급 토큰 수명. 짧을수록 유출/캐싱 노출 윈도우가 작다.
 * @property clockSkewSeconds 검증 시 허용하는 시계 오차.
 * @property tokenCache       발급 토큰 캐싱(RS256 서명 분할상환) 설정.
 */
@ConfigurationProperties("internal-auth")
data class InternalAuthProperties(
    val issuer: IssuerConfig? = null,
    val verifier: VerifierConfig? = null,
    val ttlSeconds: Long = 60,
    val clockSkewSeconds: Long = 30,
    val tokenCache: TokenCacheConfig = TokenCacheConfig(),
) {
    /**
     * @property name          iss 클레임에 들어갈 호출자 서비스명.
     * @property privateKeyPem PKCS#8 PEM. 운영에선 환경변수/secret manager 로 주입.
     * @property keyId         JWS kid 헤더. 키 회전 시 식별용.
     */
    data class IssuerConfig(
        val name: String,
        val privateKeyPem: String,
        val keyId: String = "default",
    )

    /**
     * @property audience     이 서비스를 가리키는 aud 값. 다른 서비스용 토큰 재사용 차단.
     * @property knownIssuers 신뢰하는 호출자(iss) → 그 호출자의 공개키 PEM 맵.
     */
    data class VerifierConfig(
        val audience: String,
        val knownIssuers: Map<String, String> = emptyMap(),
    )

    /**
     * 발급 토큰 캐싱. (subject, audience, roles) 가 같은 토큰을 TTL 윈도우 동안 재사용해
     * 요청당 RS256 서명을 유저당 1회/윈도우로 줄인다.
     *
     * @property enabled              캐싱 사용 여부. jti 기반 재생 방지를 도입하면 꺼야 한다.
     * @property refreshMarginSeconds 만료 이 초 전부터 재서명(잔여 수명 보장).
     * @property maxEntries           캐시 상한(메모리 방어).
     */
    data class TokenCacheConfig(
        val enabled: Boolean = true,
        val refreshMarginSeconds: Long = 10,
        val maxEntries: Int = 10_000,
    )
}
