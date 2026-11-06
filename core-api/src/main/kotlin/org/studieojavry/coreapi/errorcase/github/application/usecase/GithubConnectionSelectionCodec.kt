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
 * installationId 와 **이 공간에서 선택 가능한(eligible) 레포 집합**까지 담는다. eligible 은 후보 조회 시점에
 * 사용자 토큰으로 계산한 "설치 grant ∩ 사용자 접근 가능 ∩ 공간 권한(개인=read/워크스페이스=admin)" 이다. 연결 생성은
 * code 재교환 없이 이 서명 집합만으로 선택을 검증하므로, 사용자 권한 재조회 없이도 권한 격리가 유지된다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubConnectionSelectionCodec(
    private val properties: GithubAppProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Selection(
        val userId: Long,
        val workspaceId: Long?,
        val installationId: Long,
        /** 후보 조회 때 확정한, 이 공간에서 선택 가능한 레포 id. 생성은 선택이 이 집합의 부분집합인지만 검증한다. */
        val eligibleRepoIds: List<Long>,
    )

    private val random = SecureRandom()

    fun issue(userId: Long, workspaceId: Long?, installationId: Long, eligibleRepoIds: List<Long>): String {
        val exp = clock.instant().plus(properties.stateTtl).epochSecond
        val nonce = ByteArray(12).also(random::nextBytes).let(ENC::encodeToString)
        val eligible = eligibleRepoIds.joinToString(",")
        val body = ENC.encodeToString(
            "$VERSION|$userId|${workspaceId ?: ""}|$installationId|$eligible|$exp|$nonce".toByteArray(StandardCharsets.UTF_8),
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
        if (parts.size != 7 || parts[0] != VERSION) throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        val exp = parts[5].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류")
        if (clock.instant().epochSecond > exp) {
            throw GithubConnectionCandidateInvalidException("후보 토큰 만료 — 연결을 다시 시작하세요")
        }
        return Selection(
            userId = parts[1].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류"),
            workspaceId = parts[2].takeIf { it.isNotEmpty() }?.toLongOrNull(),
            installationId = parts[3].toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류"),
            eligibleRepoIds = parts[4].takeIf { it.isNotEmpty() }
                ?.split(',')
                ?.map { it.toLongOrNull() ?: throw GithubConnectionCandidateInvalidException("후보 토큰 형식 오류") }
                ?: emptyList(),
        )
    }

    private fun hmac(body: String): ByteArray =
        Mac.getInstance(ALG).run {
            init(SecretKeySpec(properties.stateSecret!!.toByteArray(StandardCharsets.UTF_8), ALG))
            doFinal(body.toByteArray(StandardCharsets.UTF_8))
        }

    companion object {
        private const val VERSION = "v2"
        private const val ALG = "HmacSHA256"
        private val ENC = Base64.getUrlEncoder().withoutPadding()
        private val DEC = Base64.getUrlDecoder()
    }
}
