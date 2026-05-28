package org.studieojavry.internalauth

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.interfaces.RSAPublicKey
import java.time.Instant

/**
 * X-Internal-Auth 헤더의 internal JWT 를 검증해 SecurityContext 를 채운다.
 *
 * 헤더가 없으면 인증을 세팅하지 않고 그대로 통과시킨다 (permitAll 경로 / 다른 인증수단 fallback).
 * 헤더가 있는데 검증 실패하면 401 로 즉시 차단한다.
 *
 * 검증: 서명(iss 의 공개키) → aud 일치 → exp/iat (시계 오차 허용).
 * 메쉬 도입 후 서명/iss/aud 검증은 mTLS+AuthorizationPolicy 로 이관 가능하며,
 * 사용자 컨텍스트(sub/roles) 추출만 이 필터에 남는다.
 */
class InternalTokenAuthenticationFilter(
    private val expectedAudience: String,
    private val knownIssuers: Map<String, RSAPublicKey>,
    private val clockSkewSeconds: Long,
) : OncePerRequestFilter() {

    private val logger = KotlinLogging.logger {}

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader(HEADER_NAME)
        logger.info { "internal token header = $token" }
        if (token.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        val authentication = try {
            verify(token)
        } catch (ex: Exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid internal token")
            logger.warn("Internal token rejected: ${ex.message}")
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        logger.info { "[filter] thread=${Thread.currentThread().name}" }
        try {
            filterChain.doFilter(request, response)
            logger.info { "[after doFilter] auth=${SecurityContextHolder.getContext().authentication}" }
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun verify(token: String): InternalAuthentication {
        val jwt = SignedJWT.parse(token)
        val claims = jwt.jwtClaimsSet

        val issuer = claims.issuer ?: throw IllegalArgumentException("missing iss")
        val publicKey = knownIssuers[issuer] ?: throw IllegalArgumentException("unknown iss: $issuer")
        if (!jwt.verify(RSASSAVerifier(publicKey))) {
            throw IllegalArgumentException("bad signature")
        }

        val audience = claims.audience ?: emptyList()
        if (expectedAudience !in audience) {
            throw IllegalArgumentException("aud mismatch: $audience")
        }

        val now = Instant.now()
        val exp = claims.expirationTime?.toInstant() ?: throw IllegalArgumentException("missing exp")
        if (now.minusSeconds(clockSkewSeconds).isAfter(exp)) {
            throw IllegalArgumentException("expired")
        }
        val iat = claims.issueTime?.toInstant() ?: throw IllegalArgumentException("missing iat")
        if (now.plusSeconds(clockSkewSeconds).isBefore(iat)) {
            throw IllegalArgumentException("issued in future")
        }

        val userId = claims.subject?.toLongOrNull()
            ?: throw IllegalArgumentException("missing/invalid sub")
        val roles = runCatching { claims.getStringListClaim("roles") }.getOrNull().orEmpty()
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }

        return InternalAuthentication(userId, authorities, issuer)
    }

    companion object {
        const val HEADER_NAME = "X-Internal-Auth"
    }
}
