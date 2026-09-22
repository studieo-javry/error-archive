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
import java.util.concurrent.ConcurrentHashMap

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
 *
 * ## 토큰 캐싱 (RS256 서명 분할상환)
 * RSA 개인키 서명은 요청당 CPU 를 크게 차지한다(1 vCPU 공유 노드에서 병목). 발급 토큰은
 * (subject, audience, roles) 가 같으면 TTL 윈도우 동안 재사용 가능하므로, 그 키로 서명 결과를
 * 캐싱해 **유저당 TTL 윈도우에 한 번만 서명**한다(요청마다 X). 부하 시 서명 횟수가 극적으로 준다.
 *
 * 안전성: 캐시 재사용은 같은 jti 를 반복 사용하지만, 검증 측(InternalTokenAuthenticationFilter)이
 * jti(nonce)를 재생 방지에 쓰지 않으므로 안전하다. 향후 jti 기반 재생 방지를 도입하면 이 캐싱과
 * 상충하므로 [cacheEnabled] 를 꺼야 한다.
 *
 * @param refreshMarginSeconds 만료 [refreshMarginSeconds] 초 전부터는 재사용을 멈추고 새로 서명한다.
 *        건네받는 토큰이 항상 최소 이만큼의 잔여 수명을 갖도록 보장(다운스트림 처리·시계오차 여유).
 * @param maxCacheEntries 캐시 상한. 초과 시 만료 임박 엔트리를 청소해 메모리 무한 증가를 막는다.
 */
class InternalTokenIssuer(
    private val issuerName: String,
    privateKey: RSAPrivateKey,
    private val keyId: String,
    private val ttlSeconds: Long,
    private val cacheEnabled: Boolean = true,
    private val refreshMarginSeconds: Long = DEFAULT_REFRESH_MARGIN_SECONDS,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    private val signer = RSASSASigner(privateKey)

    /** key = subject|audience|roles → 서명된 토큰 + 재서명 시점. */
    private val cache = ConcurrentHashMap<String, CachedToken>()

    /** 재사용 윈도우: TTL 에서 여유분을 뺀 만큼(최소 1초). 이 시간이 지나면 다시 서명한다. */
    private val reuseWindowSeconds: Long = (ttlSeconds - refreshMarginSeconds).coerceAtLeast(1)

    fun issue(subject: String, audience: String, roles: List<String>): String {
        val now = Instant.now()
        if (!cacheEnabled) return sign(subject, audience, roles, now)

        val key = cacheKey(subject, audience, roles)
        cache[key]?.let { if (now.isBefore(it.refreshAt)) return it.token }

        // 미스이거나 만료 임박 → 새로 서명하고 캐시 갱신.
        val token = sign(subject, audience, roles, now)
        cache[key] = CachedToken(token, now.plusSeconds(reuseWindowSeconds))
        if (cache.size > maxCacheEntries) evictStale(now)
        return token
    }

    private fun sign(subject: String, audience: String, roles: List<String>, now: Instant): String {
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

    /** roles 순서에 무관하도록 정렬. 값에 등장하지 않는 제어문자를 구분자로 사용. */
    private fun cacheKey(subject: String, audience: String, roles: List<String>): String =
        buildString {
            append(subject).append(SEP)
            append(audience).append(SEP)
            roles.sorted().joinTo(this, ROLE_SEP)
        }

    /** 상한 초과 시 재서명 시점이 지난(어차피 다시 서명될) 엔트리를 제거해 증가를 억제. */
    private fun evictStale(now: Instant) {
        cache.entries.removeIf { !now.isBefore(it.value.refreshAt) }
    }

    private class CachedToken(val token: String, val refreshAt: Instant)

    companion object {
        const val DEFAULT_REFRESH_MARGIN_SECONDS = 10L
        const val DEFAULT_MAX_CACHE_ENTRIES = 10_000
        private const val SEP = '\u001f'       // Unit Separator
        private const val ROLE_SEP = "\u001e"  // Record Separator
    }
}
