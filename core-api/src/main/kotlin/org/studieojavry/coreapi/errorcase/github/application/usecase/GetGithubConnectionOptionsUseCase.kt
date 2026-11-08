package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 연결 옵션 조회(시나리오 ②) — 사용자가 **이미 저장된 토큰**을 쓰면 GitHub 로 다시 보내지 않고 바로 후보를 보여준다.
 *
 * FE 가 "GitHub 연결" 을 누르면 먼저 이걸 호출해 상황을 구분한다([DiscoveryStatus]):
 *  - NOT_AUTHORIZED     : 저장된(유효한) 토큰 없음 → [authorizeUrl] 로 보내 OAuth 인가를 받아야 함.
 *  - VERIFIED_EMPTY     : 인가는 됐지만 접근 가능한 App 설치가 없음 → [installUrl] 로 보내 설치부터.
 *  - VERIFIED_AVAILABLE : 접근 가능한 설치가 있음 → [candidates] 로 바로 레포 선택(= GitHub 재이동 생략).
 *  - TEMPORARY_FAILURE  : GitHub 일시 장애 → 잠시 후 재시도.
 *
 * "설치가 없음"(VERIFIED_EMPTY)과 "인가가 안 됨"(NOT_AUTHORIZED)을 구분하는 게 핵심 — 둘을 뭉치면 FE 가 잘못 안내한다.
 *
 * 토큰 수명: 저장 토큰은 refresh 토큰이 **함께 저장돼 있을 때만** 선제 갱신한다([needsRefresh]). refresh 토큰은
 * "6개월 기억" 동의([remember])를 한 사용자에게만 저장되므로(후보 조회 단계에서 결정), 기본(미동의)은 단명 토큰만 있어
 * ~8h 뒤 만료되면 폐기→NOT_AUTHORIZED(재인가)로 떨어진다. 동의한 사용자는 만료 임박 시 refresh 토큰으로 선제 갱신해
 * 시나리오 ②가 재인가 없이 이어진다. refresh 토큰이 만료·취소됐으면 폐기하고 NOT_AUTHORIZED. 갱신 뒤에도(또는 갱신 없이)
 * 설치 조회가 4xx 면(취소 등) 그 토큰을 폐기하고 NOT_AUTHORIZED. [remember] 는 재인가가 필요할 때 authorizeUrl state 로
 * 다시 실어 보내, 왕복 뒤 동의가 보존되게 한다.
 */
@Component
@ConditionalOnGithubEnabled
class GetGithubConnectionOptionsUseCase(
    private val userAuthorizations: GithubUserAuthorizationPort,
    private val githubApi: GithubAppApiPort,
    private val candidates: GetGithubConnectionCandidatesUseCase,
    private val issueAuthorizeUrl: IssueGithubAuthorizeUrlUseCase,
    private val issueInstallUrl: IssueGithubInstallUrlUseCase,
    private val workspaceQuery: WorkspaceQueryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = KotlinLogging.logger {}

    enum class DiscoveryStatus { NOT_AUTHORIZED, VERIFIED_EMPTY, VERIFIED_AVAILABLE, TEMPORARY_FAILURE }

    data class Result(
        val status: DiscoveryStatus,
        val workspaceId: Long?,
        /** NOT_AUTHORIZED 일 때 FE 가 이동할 OAuth 인가 URL. 그 외엔 null. */
        val authorizeUrl: String?,
        /** VERIFIED_EMPTY 일 때 FE 가 이동할 설치 URL. 그 외엔 null. */
        val installUrl: String?,
        /** VERIFIED_AVAILABLE 일 때 바로 쓸 후보(설치 + grant 레포 + candidateToken). 그 외엔 빈 목록. */
        val candidates: List<GetGithubConnectionCandidatesUseCase.Candidate>,
    )

    fun invoke(userId: Long, workspaceId: Long?, remember: Boolean = false): Result {
        workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, userId, it) }
        val space: GithubSpace = workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(userId)

        val found = userAuthorizations.findByEaUserId(userId)
            ?: return notAuthorized(userId, workspaceId, remember)

        // 0) 만료 임박이면 설치 조회 전에 선제 갱신(App 이 토큰 만료를 켠 경우). 갱신 재료 없으면 그대로 둔다.
        val stored = if (found.needsRefresh(Instant.now(clock), REFRESH_MARGIN)) {
            when (val outcome = refresh(userId, found)) {
                is Refreshed -> outcome.authorization
                Reauthorize -> return notAuthorized(userId, workspaceId, remember)
                is Transient -> return temporaryFailure(workspaceId, outcome.cause, userId)
            }
        } else {
            found
        }

        // 1) 설치 목록 조회는 **사용자 토큰** 호출 — 여기의 4xx(만료·취소)만 토큰 폐기 근거로 본다(그 사용자 1건만).
        val installationIds = try {
            githubApi.listUserInstallationIds(stored.token)
        } catch (e: GithubApiException) {
            if (e.clientError) {
                userAuthorizations.deleteByEaUserId(userId)
                log.info { "stored github token invalid for user=$userId — purged, re-auth required" }
                return notAuthorized(userId, workspaceId, remember)
            }
            return temporaryFailure(workspaceId, e, userId)
        }
        if (installationIds.isEmpty()) {
            return Result(DiscoveryStatus.VERIFIED_EMPTY, workspaceId, authorizeUrl = null, installUrl = issueInstallUrl.invoke(userId, workspaceId, remember), candidates = emptyList())
        }

        // 2) 토큰은 1)에서 유효 확인됨 — 이후 후보 조립에서 나는 GitHub 오류(설치 메타/레포 등 App·설치토큰 호출 포함)는
        //    토큰 문제가 아니므로 폐기하지 않고 일시 장애로 본다.
        val result = try {
            candidates.candidatesFor(userId, workspaceId, space, stored.token, installationIds)
        } catch (e: GithubApiException) {
            return temporaryFailure(workspaceId, e, userId)
        }
        return Result(DiscoveryStatus.VERIFIED_AVAILABLE, workspaceId, authorizeUrl = null, installUrl = null, candidates = result.candidates)
    }

    private fun temporaryFailure(workspaceId: Long?, e: GithubApiException, userId: Long): Result {
        log.warn(e) { "github connection options temporary failure for user=$userId" }
        return Result(DiscoveryStatus.TEMPORARY_FAILURE, workspaceId, authorizeUrl = null, installUrl = null, candidates = emptyList())
    }

    private fun notAuthorized(userId: Long, workspaceId: Long?, remember: Boolean) =
        Result(DiscoveryStatus.NOT_AUTHORIZED, workspaceId, authorizeUrl = issueAuthorizeUrl.invoke(userId, workspaceId, remember), installUrl = null, candidates = emptyList())

    /**
     * refresh 토큰으로 사용자 토큰을 갱신하고 새 토큰(+회전된 refresh 토큰·만료)을 저장한다. GitHub 가 refresh 토큰을
     * 회전하므로 다음 갱신을 위해 응답값을 그대로 저장해야 한다. refresh 응답에서 값이 빠지면(이례적) 기존 값을 유지한다.
     */
    private fun refresh(userId: Long, stored: GithubUserAuthorization): RefreshOutcome {
        val refreshToken = stored.refreshToken ?: return Reauthorize // needsRefresh 가 보장하지만 방어
        val refreshed = try {
            githubApi.refreshUserToken(refreshToken)
        } catch (e: GithubApiException) {
            if (e.clientError) {
                userAuthorizations.deleteByEaUserId(userId)
                log.info { "github refresh token invalid for user=$userId — purged, re-auth required" }
                return Reauthorize
            }
            return Transient(e)
        }
        val now = Instant.now(clock)
        val saved = userAuthorizations.save(
            stored.copy(
                token = refreshed.token,
                refreshToken = refreshed.refreshToken ?: stored.refreshToken,
                expiresAt = refreshed.expiresAt ?: stored.expiresAt,
                refreshTokenExpiresAt = refreshed.refreshTokenExpiresAt ?: stored.refreshTokenExpiresAt,
                updatedAt = now,
            ),
        )
        log.info { "github user token refreshed for user=$userId" }
        return Refreshed(saved)
    }

    private sealed interface RefreshOutcome
    private data class Refreshed(val authorization: GithubUserAuthorization) : RefreshOutcome
    /** refresh 토큰이 만료·취소됨 → 저장 행 폐기 완료, 재인가 필요. */
    private data object Reauthorize : RefreshOutcome
    /** GitHub 일시 장애 → 폐기하지 않고 잠시 후 재시도. */
    private data class Transient(val cause: GithubApiException) : RefreshOutcome

    private companion object {
        /** 이 여유 안에 들면 아직 유효해도 미리 갱신(경계에서의 만료·시계 오차 흡수). installation 토큰 갱신 여유와 동일. */
        private val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
