package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

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
 * 저장 토큰이 만료·취소됐으면(= 사용자 토큰 호출이 4xx) 그 토큰을 폐기하고 NOT_AUTHORIZED 로 떨어뜨린다.
 */
@Component
@ConditionalOnGithubEnabled
class GetGithubConnectionOptionsUseCase(
    private val userAuthorizations: GithubUserAuthorizationPort,
    private val candidates: GetGithubConnectionCandidatesUseCase,
    private val issueAuthorizeUrl: IssueGithubAuthorizeUrlUseCase,
    private val issueInstallUrl: IssueGithubInstallUrlUseCase,
    private val workspaceQuery: WorkspaceQueryPort,
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

    fun invoke(userId: Long, workspaceId: Long?): Result {
        workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, userId, it) }
        val space: GithubSpace = workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(userId)

        val stored = userAuthorizations.findByEaUserId(userId)
            ?: return notAuthorized(userId, workspaceId)

        val result = try {
            candidates.candidatesFor(userId, workspaceId, space, stored.token)
        } catch (e: GithubApiException) {
            if (e.clientError) {
                // 저장 토큰이 만료·취소됨 — 폐기하고 재인가로 유도.
                userAuthorizations.deleteByGithubUserId(stored.githubUserId)
                log.info { "stored github token invalid for user=$userId — purged, re-auth required" }
                return notAuthorized(userId, workspaceId)
            }
            log.warn(e) { "github connection options temporary failure for user=$userId" }
            return Result(DiscoveryStatus.TEMPORARY_FAILURE, workspaceId, authorizeUrl = null, installUrl = null, candidates = emptyList())
        }

        return if (result.candidates.isEmpty()) {
            Result(DiscoveryStatus.VERIFIED_EMPTY, workspaceId, authorizeUrl = null, installUrl = issueInstallUrl.invoke(userId, workspaceId), candidates = emptyList())
        } else {
            Result(DiscoveryStatus.VERIFIED_AVAILABLE, workspaceId, authorizeUrl = null, installUrl = null, candidates = result.candidates)
        }
    }

    private fun notAuthorized(userId: Long, workspaceId: Long?) =
        Result(DiscoveryStatus.NOT_AUTHORIZED, workspaceId, authorizeUrl = issueAuthorizeUrl.invoke(userId, workspaceId), installUrl = null, candidates = emptyList())
}
