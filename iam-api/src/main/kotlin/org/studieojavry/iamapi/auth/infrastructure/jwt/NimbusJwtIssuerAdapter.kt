package org.studieojavry.iamapi.auth.infrastructure.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.port.JwtIssuerPort
import org.studieojavry.iamapi.auth.config.JwtProperties
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class NimbusJwtIssuerAdapter(
    private val jwtProperties: JwtProperties
) : JwtIssuerPort {

    private val signer = MACSigner(jwtProperties.secret.toByteArray(Charsets.UTF_8))

    override fun issueAccessToken(userId: Long, expiresAt: Instant): String =
        sign(userId, expiresAt, typ = "access")

    override fun issueSseTicket(userId: Long, expiresAt: Instant): String =
        sign(userId, expiresAt, typ = "sse")

    private fun sign(userId: Long, expiresAt: Instant, typ: String): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(jwtProperties.issuer)
            .audience(jwtProperties.audience)
            .subject(userId.toString())
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString())
            .claim("typ", typ)
            .build()

        val header = JWSHeader.Builder(JWSAlgorithm.HS256)
            .type(JOSEObjectType.JWT)
            .build()

        val jwt = SignedJWT(header, claims)
        jwt.sign(signer)
        return jwt.serialize()
    }
}