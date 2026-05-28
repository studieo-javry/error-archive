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
 */
@ConfigurationProperties("internal-auth")
data class InternalAuthProperties(
    val issuer: IssuerConfig? = null,
    val verifier: VerifierConfig? = null,
    val ttlSeconds: Long = 60,
    val clockSkewSeconds: Long = 30,
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
}
