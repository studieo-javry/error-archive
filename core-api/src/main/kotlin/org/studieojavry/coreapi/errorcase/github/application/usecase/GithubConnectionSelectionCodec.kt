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
 * 후보 선택 토큰 — `connection-candidates` 가 **OAuth code 를 한 번 교환해 설치 소유를 확인한 뒤**,
 * "이 사용자가 이 공간으로 이 설치를 연결해도 된다" 를 서명해 내려 준다. FE 는 레포를 고른 다음
 * 이 토큰과 함께 `POST /connections` 를 부르고, 서버는 **code 재교환 없이** 토큰 서명만으로 소유를 신뢰한다.
 *
 * 형식·서명은 [GithubInstallStateCodec] 와 동일(무상태 HMAC-SHA256, stateSecret, TTL). state 와 달리
 * installationId 까지 담아, 후보 조회 시점에 확인된 "소유한 설치"만 연결에 쓰도록 묶는다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubConnectionSelectionCodec(
    private val properties: GithubAppProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Selection(val userId: Long, val workspaceId: Long?, val installationId: Long)

    private val random = SecureRandom()

    fun issue(userId: Long, workspaceId: Long?, installationId: Long): String {
        val exp = clock.instant().plus(properties.stateTtl).epochSecond
        val nonce = ByteArray(12).also(random::nextBytes).let(ENC::encodeToString)
        val body = ENC.encodeToString(
            "$VERSION|$userId|${workspaceId ?: ""}|$installationId|$exp|$nonce".toByteArray(StandardCharsets.UTF_8),
        )
        return body + "." + ENC.encodeToString(hmac(body))
    }

    /** 위조·형식오류·만료면 [GithubConnectionCandidateInvalidException]. */
    fun verify(token: String): Selection {
        val dot = token.indexOf('.')
        if (dot <= 0) throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        val body = token.substring(0, dot)
        val sig = runCatching { DEC.decode(token.substring(dot + 1)) }.getOrNull()
            ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        if (!MessageDigest.isEqual(hmac(body), sig)) throw GithubConnectionCandidateInvalidException("후보 토큰 서명 불일치")

        val parts = String(DEC.decode(body), StandardCharsets.UTF_8).split('|')
        if (parts.size != 6 || parts[0] != VERSION) throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        val exp = parts[4].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        if (clock.instant().epochSecond > exp) {
            throw GithubConnectionCandidateInvalidException("후보 토큰 만료 — 연결을 다시 시작하세요")
        }
        return Selection(
            userId = parts[1].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류"),
            workspaceId = parts[2].takeIf { it.isNotEmpty() }?.toLongOrNull(),
            installationId = parts[3].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류"),
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
