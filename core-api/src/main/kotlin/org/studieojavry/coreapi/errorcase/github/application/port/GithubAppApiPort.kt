package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind

/**
 * GitHub API 호출 경계. 구현은 App JWT / installation 토큰 / 사용자 토큰을 상황별로 고른다.
 * 실패는 [GithubApiException] — 사용자 오류(잘못된 code 등)는 [GithubApiException.clientError]=true.
 */
interface GithubAppApiPort {

    /** 설치 콜백의 `code` → 사용자 토큰. 설치 소유 검증([listUserInstallationIds])에만 쓰고 버린다. */
    fun exchangeUserCode(code: String): String

    /** 이 사용자 토큰으로 접근 가능한 App 설치 id 목록 (`GET /user/installations`). */
    fun listUserInstallationIds(userToken: String): Set<Long>

    /** 설치 메타 (`GET /app/installations/{id}`, App JWT). */
    fun getInstallation(installationId: Long): InstallationInfo

    /** 설치 대상 레포 전체 (`GET /installation/repositories`, installation 토큰). */
    fun listInstallationRepositories(installationId: Long): List<RepositoryInfo>

    /**
     * Issue 또는 PR 한 건 (GraphQL `issueOrPullRequest`). 없거나 접근 불가면 null.
     * 주소가 `/issues/N` 이어도 실제가 PR 이면 [PullRequestItem] — GitHub 웹도 그렇게 리다이렉트한다.
     */
    fun fetchItem(installationId: Long, owner: String, name: String, number: Int): GithubItem?

    /** 커밋 하나의 check run 전부 (`GET /repos/{o}/{r}/commits/{sha}/check-runs`). */
    fun fetchCommitCheckRuns(installationId: Long, owner: String, name: String, sha: String): List<CiSummary.Run>

    /**
     * 설치 안 레포들에서 Issue·PR 제목 검색 (GraphQL `search`, type ISSUE). [text] 가 비면 최근 갱신순.
     * GitHub 검색은 설치 토큰 기준 분당 호출 한도가 있어 호출 쪽에서 캐시한다.
     */
    fun searchItems(installationId: Long, repoFullNames: List<String>, text: String, limit: Int): List<SearchHit>

    /**
     * 레포의 최근 생성 Issue [limit]개 + PR [limit]개 (GraphQL, 생성 역순). `#9` → `#95` 같은 번호 앞자리 찾기용 —
     * GitHub 검색은 번호 앞자리를 못 찾아서 최근 목록을 받아 호출 쪽에서 거른다. 레포가 없으면 빈 목록.
     */
    fun listRecentItems(installationId: Long, owner: String, name: String, limit: Int): List<SearchHit>

    sealed interface GithubItem { val repoId: Long }

    /** 검색 결과 한 줄 — [state] 는 Issue `OPEN|CLOSED`, PR `OPEN|CLOSED|MERGED`. */
    data class SearchHit(val repoFullName: String, val number: Int, val kind: GithubRefKind, val title: String, val state: String)

    data class IssueItem(
        override val repoId: Long,
        val snapshot: GithubIssueSnapshot,
        val body: String?,
        val labels: List<String>,
        /** Issue 타임라인에서 이 Issue 를 언급·연결한 PR(최대 50 이벤트) — "닫는 PR" 인지는 PR 쪽 closingIssuesReferences 로 확정. */
        val pullRequestCandidates: List<PullRequestCandidate> = emptyList(),
    ) : GithubItem

    data class PullRequestCandidate(val repoId: Long, val fullName: String, val number: Int)

    data class PullRequestItem(
        override val repoId: Long,
        val snapshot: GithubPullRequestSnapshot,
        val body: String?,
        val defaultBranch: String?,
        /** GitHub 가 판정한 "이 PR 이 닫는 Issue" (closingIssuesReferences — 사이드바 수동 연결 포함). */
        val closingIssues: List<GithubItemRef>,
    ) : GithubItem

    data class InstallationInfo(val id: Long, val accountLogin: String, val accountType: String)
    data class RepositoryInfo(val id: Long, val fullName: String, val isPrivate: Boolean)
}

class GithubApiException(message: String, val clientError: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)
