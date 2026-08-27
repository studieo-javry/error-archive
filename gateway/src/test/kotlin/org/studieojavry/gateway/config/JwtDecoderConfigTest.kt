package org.studieojavry.gateway.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.util.Date

/**
 * JwtDecoderConfig 단위 테스트 — 사용자 JWT 검증 게이트.
 *
 * 운영 관점: gateway 는 이 검증만 통과시키면 downstream 으로 신원을 전파한다. 따라서
 * issuer / audience / typ / 만료 / 서명 중 하나라도 어긋나면 반드시 거부해야 한다.
 * typ 은 access + sse 둘 다 허용(EventSource 단명 ticket).
 */
class JwtDecoderConfigTest {

    private val secret = "test-secret-test-secret-test-secret-test-secret-test-secret-1234" // 64 chars ≥ 256bit
    private val issuer = "https://iam.test"
    private val audience = "gateway-clients"
    private val decoder = JwtDecoderConfig().jwtDecoder(JwtProperties(secret, issuer, audience))

    private fun token(
        iss: String = issuer,
        aud: String = audience,
        typ: String = "access",
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        signingSecret: String = secret,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(iss)
            .audience(aud)
            .subject("user-1")
            .claim("typ", typ)
            .issueTime(Date())
            .expirationTime(Date.from(expiresAt))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(signingSecret.toByteArray())) }
            .serialize()
    }

    @Test
    fun `유효한 access 토큰은 통과`() {
        val jwt = decoder.decode(token())
        assertEquals("user-1", jwt.subject)
    }

    @Test
    fun `typ=sse 토큰도 통과`() {
        assertDoesNotThrow { decoder.decode(token(typ = "sse")) }
    }

    @Test
    fun `issuer 불일치는 거부`() {
        assertThrows<JwtException> { decoder.decode(token(iss = "https://evil.example")) }
    }

    @Test
    fun `audience 불일치는 거부`() {
        assertThrows<JwtException> { decoder.decode(token(aud = "other-clients")) }
    }

    @Test
    fun `허용되지 않은 typ 는 거부`() {
        assertThrows<JwtException> { decoder.decode(token(typ = "refresh")) }
    }

    @Test
    fun `만료된 토큰은 거부`() {
        assertThrows<JwtException> { decoder.decode(token(expiresAt = Instant.now().minusSeconds(60))) }
    }

    @Test
    fun `다른 키로 서명한 토큰은 거부`() {
        val forged = token(signingSecret = "another-secret-another-secret-another-secret-another-secret-abcd")
        assertThrows<JwtException> { decoder.decode(forged) }
    }
}
