package org.studieojavry.coreapi.errorcase.github.infrastructure.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub REST/GraphQL 호출 어댑터 — 인증 3종을 용도별로 사용.
 *  - App JWT: `/app/...` (설치 메타, installation 토큰 발급)
 *  - installation 토큰(1h, 메모리 캐시·만료 5분 전 갱신): 레포 조회 등 설치 범위 호출
 *  - 사용자 토큰: 설치 소유 검증(`/user/installations`) 1회용
 *
 * 모든 호출은 circuit breaker `github` 로 감싼다. 4xx 는 사용자/설정 오류라 그대로 [GithubApiException](clientError) 로.
 */
@Component
@ConditionalOnGithubEnabled
class GithubAppApiAdapter(
    private val properties: GithubAppProperties,
    cbFactory: CircuitBreakerFactory<*, *>,
) : GithubAppApiPort {

    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("github")
    private val jwtSigner = GithubAppJwtSigner(properties.appId!!, properties.privateKeyPem!!)
    private val tokenCache = ConcurrentHashMap<Long, CachedToken>()

    private val requestFactory = ClientHttpRequestFactoryBuilder.detect().build(
        HttpClientSettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(2))
            .withReadTimeout(Duration.ofSeconds(4)),
    )

    private val api: RestClient = RestClient.builder()
        .baseUrl(properties.apiBaseUrl)
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", API_VERSION)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    private val web: RestClient = RestClient.builder()
        .baseUrl(properties.webBaseUrl)
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    override fun exchangeUserCode(code: String): String = call("exchangeUserCode") {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
            add("code", code)
        }
        val res = web.post().uri("/login/oauth/access_token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(OAuthTokenResponse::class.java)
        // GitHub 는 잘못된 code 에도 200 + {"error":"bad_verification_code"} 로 응답한다.
        res?.accessToken ?: throw GithubApiException("GitHub 인가 코드가 유효하지 않음: ${res?.error}", clientError = true)
    }

    override fun listUserInstallationIds(userToken: String): Set<Long> = call("listUserInstallations") {
        paginate { page ->
            api.get().uri("/user/installations?per_page={n}&page={p}", PAGE_SIZE, page)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .body(UserInstallationsResponse::class.java)
                ?.installations.orEmpty()
                .map { it.id }
        }.toSet()
    }

    override fun getInstallation(installationId: Long): GithubAppApiPort.InstallationInfo = call("getInstallation") {
        val res = api.get().uri("/app/installations/{id}", installationId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtSigner.sign()}")
            .retrieve()
            .body(InstallationResponse::class.java)
            ?: throw GithubApiException("설치 정보 응답이 비어 있음: $installationId")
        GithubAppApiPort.InstallationInfo(res.id, res.account.login, res.account.type)
    }

    override fun listInstallationRepositories(installationId: Long): List<GithubAppApiPort.RepositoryInfo> =
        call("listInstallationRepositories") {
            val token = installationToken(installationId)
            paginate { page ->
                api.get().uri("/installation/repositories?per_page={n}&page={p}", PAGE_SIZE, page)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(InstallationRepositoriesResponse::class.java)
                    ?.repositories.orEmpty()
                    .map { GithubAppApiPort.RepositoryInfo(it.id, it.fullName, it.isPrivate) }
            }
        }

    override fun fetchItem(installationId: Long, owner: String, name: String, number: Int): GithubAppApiPort.GithubItem? =
        call("fetchItem") {
            val token = installationToken(installationId)
            val res = api.post().uri("/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("query" to ITEM_QUERY, "variables" to mapOf("owner" to owner, "name" to name, "number" to number)))
                .retrieve()
                .body(GraphqlResponse::class.java)
            // GraphQL 은 없는 대상도 200 + errors(NOT_FOUND) 로 준다 → repository/item 이 null 이면 "없음".
            val repo = res?.data?.repository ?: return@call null
            val item = repo.issueOrPullRequest ?: return@call null
            item.toDomain(repo)
        }

    override fun fetchCommitCheckRuns(installationId: Long, owner: String, name: String, sha: String): List<CiSummary.Run> =
        call("fetchCommitCheckRuns") {
            val token = installationToken(installationId)
            api.get().uri("/repos/{o}/{r}/commits/{sha}/check-runs?per_page={n}", owner, name, sha, PAGE_SIZE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(CheckRunsResponse::class.java)
                ?.checkRuns.orEmpty()
                .map { CiSummary.Run(it.name, it.status, it.conclusion, it.completedAt) }
        }

    /** installation 토큰 — 만료 5분 전까지 캐시 재사용. (설치 수가 적어 단순 맵으로 충분) */
    private fun installationToken(installationId: Long): String {
        val now = Instant.now()
        tokenCache[installationId]?.takeIf { it.expiresAt.isAfter(now.plus(REFRESH_MARGIN)) }?.let { return it.value }
        val res = api.post().uri("/app/installations/{id}/access_tokens", installationId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtSigner.sign()}")
            .retrieve()
            .body(AccessTokenResponse::class.java)
            ?: throw GithubApiException("installation 토큰 응답이 비어 있음: $installationId")
        tokenCache[installationId] = CachedToken(res.token, res.expiresAt)
        return res.token
    }

    private fun <T> paginate(fetch: (page: Int) -> List<T>): List<T> {
        val all = mutableListOf<T>()
        for (page in 1..MAX_PAGES) {
            val items = fetch(page)
            all += items
            if (items.size < PAGE_SIZE) break
        }
        return all
    }

    private fun <T> call(op: String, block: () -> T): T = cb.run(
        {
            try {
                block()
            } catch (ex: RestClientResponseException) {
                val client = ex.statusCode.is4xxClientError
                log.warn { "github api $op failed: status=${ex.statusCode}" }
                throw GithubApiException("GitHub API $op 실패: ${ex.statusCode}", clientError = client, cause = ex)
            }
        },
        { throwable ->
            throw (throwable as? GithubApiException)
                ?: (throwable?.cause as? GithubApiException)
                ?: GithubApiException("GitHub API $op 사용 불가", cause = throwable)
        },
    )

    private data class CachedToken(val value: String, val expiresAt: Instant)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OAuthTokenResponse(
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("error") val error: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Account(val login: String, val type: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InstallationResponse(val id: Long, val account: Account)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class UserInstallationsResponse(val installations: List<InstallationResponse> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RepositoryResponse(
        val id: Long,
        @JsonProperty("full_name") val fullName: String,
        @JsonProperty("private") val isPrivate: Boolean,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InstallationRepositoriesResponse(val repositories: List<RepositoryResponse> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AccessTokenResponse(
        val token: String,
        @JsonProperty("expires_at") val expiresAt: Instant,
    )

    // ── GraphQL (Issue/PR 한 건) ──────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlResponse(val data: GraphqlData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlData(val repository: GraphqlRepository? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlRepository(
        val databaseId: Long,
        val defaultBranchRef: GraphqlName? = null,
        val issueOrPullRequest: GraphqlItem? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlName(val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlLogin(val login: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlNodes<T>(val nodes: List<T?> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlIssueRef(val number: Int, val repository: GraphqlRepoId)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlRepoId(val databaseId: Long)

    /** Issue/PR 공용 — `__typename` 으로 구분, 나머지 필드는 해당 타입일 때만 채워짐. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphqlItem(
        @JsonProperty("__typename") val typename: String,
        val number: Int,
        val title: String,
        val url: String,
        val state: String,
        val body: String? = null,
        val author: GraphqlLogin? = null,
        val createdAt: Instant,
        val updatedAt: Instant,
        val closedAt: Instant? = null,
        val labels: GraphqlNodes<GraphqlName>? = null,
        val isDraft: Boolean? = null,
        val mergedAt: Instant? = null,
        val headRefOid: String? = null,
        val baseRefName: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        val changedFiles: Int? = null,
        val closingIssuesReferences: GraphqlNodes<GraphqlIssueRef>? = null,
    ) {
        fun toDomain(repo: GraphqlRepository): GithubAppApiPort.GithubItem = if (typename == "PullRequest") {
            GithubAppApiPort.PullRequestItem(
                repoId = repo.databaseId,
                snapshot = GithubPullRequestSnapshot(
                    repo.databaseId, number, title, url, state, isDraft ?: false, author?.login,
                    headRefOid.orEmpty(), baseRefName, additions, deletions, changedFiles,
                    createdAt, mergedAt, closedAt, updatedAt,
                ),
                body = body,
                defaultBranch = repo.defaultBranchRef?.name,
                closingIssues = closingIssuesReferences?.nodes.orEmpty().filterNotNull()
                    .map { GithubItemRef(it.repository.databaseId, it.number) },
            )
        } else {
            GithubAppApiPort.IssueItem(
                repoId = repo.databaseId,
                snapshot = GithubIssueSnapshot(
                    repo.databaseId, number, title, url, state, author?.login, createdAt, closedAt, updatedAt,
                ),
                body = body,
                labels = labels?.nodes.orEmpty().mapNotNull { it?.name },
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CheckRun(
        val name: String,
        val status: String,
        val conclusion: String? = null,
        @JsonProperty("completed_at") val completedAt: Instant? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CheckRunsResponse(@JsonProperty("check_runs") val checkRuns: List<CheckRun> = emptyList())

    companion object {
        private val ITEM_QUERY = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                databaseId
                defaultBranchRef { name }
                issueOrPullRequest(number: ${'$'}number) {
                  __typename
                  ... on Issue {
                    number title url state body createdAt updatedAt closedAt
                    author { login }
                    labels(first: 20) { nodes { name } }
                  }
                  ... on PullRequest {
                    number title url state body createdAt updatedAt closedAt mergedAt isDraft
                    author { login }
                    headRefOid baseRefName additions deletions changedFiles
                    closingIssuesReferences(first: 20) { nodes { number repository { databaseId } } }
                  }
                }
              }
            }
        """.trimIndent()

        const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "error-archive-github-app"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
        private val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
