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

    /**
     * 이 사용자 토큰의 GitHub 계정 (`GET /user`). 저장한 사용자 인가(암호화 토큰) 행을
     * `github_app_authorization` revoked 웹훅의 `sender.id` 와 잇는 **안정적인 키**(id 는 rename 에도 불변)로 쓴다.
     */
    fun getAuthenticatedUser(userToken: String): GithubUserIdentity

    /** 설치 메타 (`GET /app/installations/{id}`, App JWT). */
    fun getInstallation(installationId: Long): InstallationInfo

    /** 설치 대상 레포 전체 (`GET /installation/repositories`, installation 토큰). */
    fun listInstallationRepositories(installationId: Long): List<RepositoryInfo>

    /**
     * 이 **사용자 토큰**으로 설치 [installationId] 에서 접근 가능한 레포 + 사용자 권한
     * (`GET /user/installations/{installation_id}/repositories`). 설치 grant 와 **교집합**해 후보의
     * "이 사용자가 실제로 볼 수 있고, 공간에 쓸 권한이 있는가"(개인=read / 워크스페이스 공유=admin)를 서버가 판정한다.
     * 설치 grant 에는 있어도 사용자가 접근 못 하는 레포는 목록에 넣지 않는다(비공개 이름 노출 방지).
     */
    fun listUserInstallationRepositories(userToken: String, installationId: Long): List<UserRepositoryInfo>

    /**
     * Issue 또는 PR 한 건 (GraphQL `issueOrPullRequest`). 없거나 접근 불가면 null.
     * 주소가 `/issues/N` 이어도 실제가 PR 이면 [PullRequestItem] — GitHub 웹도 그렇게 리다이렉트한다.
     */
    fun fetchItem(installationId: Long, owner: String, name: String, number: Int): GithubItem?

    /** 커밋 하나의 check run 전부 (`GET /repos/{o}/{r}/commits/{sha}/check-runs`). */
    fun fetchCommitCheckRuns(installationId: Long, owner: String, name: String, sha: String): List<CiSummary.Run>

    /**
     * 이 커밋이 들어 있는 PR 번호들 (`GET /repos/{o}/{r}/commits/{sha}/pulls`) — 머지 커밋도 해당 PR 로 찾아진다.
     * check_suite 웹훅의 `pull_requests` 가 비어 오는 push(머지) CI 를 PR 에 잇는 데 쓴다.
     */
    fun listPullRequestNumbersForCommit(installationId: Long, owner: String, name: String, sha: String): List<Int>

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

    /** `GET /user` 결과 — [id] 는 GitHub 사용자 숫자 id(불변), [login] 은 표시·감사용. */
    data class GithubUserIdentity(val id: Long, val login: String)

    data class InstallationInfo(val id: Long, val accountLogin: String, val accountType: String)
    data class RepositoryInfo(val id: Long, val fullName: String, val isPrivate: Boolean)

    /** 사용자 토큰 기준 레포 접근 권한. [canPull] 이면 최소 읽기 가능(후보에 노출), [canAdmin] 이면 워크스페이스 공유 가능. */
    data class UserRepositoryInfo(val id: Long, val canAdmin: Boolean, val canPush: Boolean, val canPull: Boolean)
}

/** [retryAfter] 가 있으면 호출 한도 초과(403/429) — 그 시간 뒤에 다시 시도하면 된다(웹훅 inbox 는 시도 횟수를 쓰지 않고 미룬다). */
class GithubApiException(
    message: String,
    val clientError: Boolean = false,
    cause: Throwable? = null,
    val retryAfter: java.time.Duration? = null,
) : RuntimeException(message, cause)
