package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 설치 state — "누가(userId) 어느 워크스페이스로 연결하려 했나" 를 서명해 GitHub 왕복에 실어 보낸다.
 *
 * 형식: `base64url(v1|userId|workspaceId|expEpochSec|nonce)` + "." + `base64url(HMAC-SHA256)`.
 * 서버 저장 없이 검증(무상태). 재사용(replay)은 막지 않는데, 완료 요청이 **로그인한 본인**(state.userId == 요청자)
 * 이고 GitHub 사용자 토큰으로 설치 소유까지 확인하므로 같은 사용자가 TTL 안에 다시 쓰는 것 외엔 의미가 없다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubInstallStateCodec(
    private val properties: GithubAppProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class State(val userId: Long, val workspaceId: Long?)

    private val random = SecureRandom()

    fun issue(userId: Long, workspaceId: Long?): String {
        val exp = clock.instant().plus(properties.stateTtl).epochSecond
        val nonce = ByteArray(12).also(random::nextBytes).let(ENC::encodeToString)
        val body = ENC.encodeToString("$VERSION|$userId|${workspaceId ?: ""}|$exp|$nonce".toByteArray(StandardCharsets.UTF_8))
        return body + "." + ENC.encodeToString(hmac(body))
    }

    /** 위조·형식오류·만료면 [GithubInstallStateInvalidException]. */
    fun verify(state: String): State {
        val dot = state.indexOf('.')
        if (dot <= 0) throw GithubInstallStateInvalidException("state 형식 오류")
        val body = state.substring(0, dot)
        val sig = runCatching { DEC.decode(state.substring(dot + 1)) }.getOrNull()
            ?: throw GithubInstallStateInvalidException("state 형식 오류")
        if (!MessageDigest.isEqual(hmac(body), sig)) throw GithubInstallStateInvalidException("state 서명 불일치")

        val parts = String(DEC.decode(body), StandardCharsets.UTF_8).split('|')
        if (parts.size != 5 || parts[0] != VERSION) throw GithubInstallStateInvalidException("state 형식 오류")
        val exp = parts[3].toLongOrNull() ?: throw GithubInstallStateInvalidException("state 형식 오류")
        if (clock.instant().epochSecond > exp) throw GithubInstallStateInvalidException("state 만료 — 연결을 다시 시작하세요")
        return State(
            userId = parts[1].toLongOrNull() ?: throw GithubInstallStateInvalidException("state 형식 오류"),
            workspaceId = parts[2].takeIf { it.isNotEmpty() }?.toLongOrNull(),
        )
    }

    private fun hmac(body: String): ByteArray =
        Mac.getInstance(ALG).run {
            init(SecretKeySpec(properties.stateSecret!!.toByteArray(StandardCharsets.UTF_8), ALG))
            doFinal(body.toByteArray(StandardCharsets.UTF_8))
        }

    companion object {
        private const val VERSION = "v1"
        private const val ALG = "HmacSHA256"
        private val ENC = Base64.getUrlEncoder().withoutPadding()
        private val DEC = Base64.getUrlDecoder()
    }
}
