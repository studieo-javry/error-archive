package org.studieojavry.internalauth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * 호출자가 downstream 서비스로 보낼 단명 internal JWT 를 발급한다 (RS256).
 *
 * 클레임:
 *   iss   = 발급 서비스명 (gateway / core-api ...)
 *   aud   = 대상 서비스명 (core-api / iam-api ...) — 다른 서비스로 재사용 차단
 *   sub   = 원 사용자 ID
 *   roles = 사용자 권한
 *   iat/exp = 단명 (기본 60초)
 *   jti   = 재생 추적용 nonce
 *
 * 메쉬 도입 후 iss/서명은 mTLS(SPIFFE)가 보장하므로 검증 측에서 그 부분만 제거하면 된다.
 */
class InternalTokenIssuer(
    private val issuerName: String,
    privateKey: RSAPrivateKey,
    private val keyId: String,
    private val ttlSeconds: Long,
) {
    private val signer = RSASSASigner(privateKey)

    fun issue(subject: String, audience: String, roles: List<String>): String {
        val now = Instant.now()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(keyId)
            .type(JOSEObjectType.JWT)
            .build()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuerName)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .jwtID(UUID.randomUUID().toString())
            .claim("roles", roles)
            .build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }
}
