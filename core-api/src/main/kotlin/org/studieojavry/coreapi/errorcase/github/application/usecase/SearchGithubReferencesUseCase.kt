package org.studieojavry.coreapi.errorcase.github.application.usecase

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import java.time.Duration

/**
 * 본문 `#` 자동완성 — 공간에 연결된 레포에서 Issue·PR 찾기(GitHub 스타일).
 *
 *  - `#` 만: 최근 갱신된 Issue·PR
 *  - `#95`: 각 레포의 **95번 그 자체** + 제목에 95 가 들어간 것 — 사용자는 대개 번호를 알고 있다
 *  - `#결제`: 제목 검색
 *
 * 결과는 레포 이름 → 번호 순, 최대 [LIMIT]개. 저장은 하지 않는다(고른 뒤 본문에 `owner/repo#N` 이 들어갈 뿐).
 * GitHub 실패는 그 레포·설치만 빼고 나머지 결과를 준다. 검색 API 는 분당 한도가 작아 같은 질의는 [CACHE_TTL] 동안 재사용.
 */
@Service
@ConditionalOnGithubEnabled
class SearchGithubReferencesUseCase(
    private val scope: GithubReferenceScope,
    private val githubApi: GithubAppApiPort,
) {
    private val log = KotlinLogging.logger {}

    data class Hit(val repo: String, val number: Int, val kind: String, val title: String, val status: String)

    private val cache: Cache<String, List<Hit>> = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL).maximumSize(1_000).build()

    fun invoke(userId: Long, workspaceId: Long?, rawQuery: String?): List<Hit> {
        val repos = scope.repositories(userId, workspaceId)
        if (repos.isEmpty()) return emptyList()
        val query = rawQuery?.trim().orEmpty().take(MAX_QUERY)
        val key = repos.joinToString(",") { it.repoId.toString() } + "|" + query.lowercase()
        return cache.get(key) { search(repos, query) }
    }

    private fun search(repos: List<GithubRepository>, query: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        val number = query.toIntOrNull()?.takeIf { it > 0 && NUMBER.matches(query) }
        if (number != null) {
            repos.take(MAX_EXACT_REPOS).forEach { repo -> exact(repo, number)?.let(hits::add) }
        }
        repos.groupBy { it.installationId }.forEach { (installationId, inInstallation) ->
            runCatching { githubApi.searchItems(installationId, inInstallation.map { it.fullName }, query, LIMIT) }
                .onFailure { log.warn { "github reference search failed: installation=$installationId cause=${it.message}" } }
                .getOrDefault(emptyList())
                .forEach { hits += Hit(it.repoFullName, it.number, it.kind.name, it.title, it.state) }
        }
        return hits.distinctBy { "${it.repo.lowercase()}#${it.number}" }
            .sortedWith(compareBy<Hit>({ it.repo.lowercase() }, { it.number }))
            .take(LIMIT)
    }

    private fun exact(repo: GithubRepository, number: Int): Hit? {
        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val item = try {
            githubApi.fetchItem(repo.installationId, owner, name, number)
        } catch (e: GithubApiException) {
            log.warn { "github reference exact lookup failed: repo=${repo.fullName}#$number cause=${e.message}" }
            null
        } ?: return null
        return when (item) {
            is GithubAppApiPort.IssueItem -> Hit(repo.fullName, number, "ISSUE", item.snapshot.title, item.snapshot.state)
            is GithubAppApiPort.PullRequestItem -> Hit(repo.fullName, number, "PULL_REQUEST", item.snapshot.title, item.snapshot.state)
        }
    }

    companion object {
        const val LIMIT = 8
        private const val MAX_QUERY = 40
        private const val MAX_EXACT_REPOS = 10
        private val NUMBER = Regex("^[1-9]\\d{0,8}$")
        private val CACHE_TTL: Duration = Duration.ofSeconds(30)
    }
}
