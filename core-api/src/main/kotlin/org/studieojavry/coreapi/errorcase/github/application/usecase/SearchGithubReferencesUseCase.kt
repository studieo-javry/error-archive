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
 * 후보: 레포마다 최근 Issue·PR 목록([RECENT_PER_KIND]개씩) + 번호면 그 번호 자체(오래된 번호도) + 글자가 있으면 제목 검색.
 * 거르기는 목업 규칙 그대로 — 입력(소문자)이 비었거나, 번호가 그걸로 **시작**하거나(`#9` → `#95`),
 * 제목·레포 이름·종류(`issue`/`pull_request`)에 들어 있으면 남긴다. GitHub 검색은 번호 앞자리를 못 찾아서 최근 목록으로 메운다.
 *
 * 결과는 레포 이름 → 번호 순, 최대 [LIMIT]개. 저장은 하지 않는다(고른 뒤 본문에 `owner/repo#N` 이 들어갈 뿐).
 * GitHub 실패는 그 레포·설치만 빼고 나머지 결과를 준다. 같은 질의는 [CACHE_TTL], 레포 최근 목록은 [RECENT_TTL] 동안 재사용.
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

    private val recentCache: Cache<Long, List<Hit>> = Caffeine.newBuilder()
        .expireAfterWrite(RECENT_TTL).maximumSize(500).build()

    fun invoke(userId: Long, workspaceId: Long?, rawQuery: String?): List<Hit> {
        val repos = scope.repositories(userId, workspaceId)
        if (repos.isEmpty()) return emptyList()
        val query = rawQuery?.trim().orEmpty().take(MAX_QUERY)
        val key = repos.joinToString(",") { it.repoId.toString() } + "|" + query.lowercase()
        return cache.get(key) { search(repos, query) }
    }

    private fun search(repos: List<GithubRepository>, query: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        repos.take(MAX_LOOKUP_REPOS).forEach { repo -> hits += recent(repo) }
        val number = query.toIntOrNull()?.takeIf { it > 0 && NUMBER.matches(query) }
        if (number != null) {
            repos.take(MAX_LOOKUP_REPOS).forEach { repo -> exact(repo, number)?.let(hits::add) }
        }
        if (query.isNotBlank()) {
            repos.groupBy { it.installationId }.forEach { (installationId, inInstallation) ->
                runCatching { githubApi.searchItems(installationId, inInstallation.map { it.fullName }, query, LIMIT) }
                    .onFailure { log.warn { "github reference search failed: installation=$installationId cause=${it.message}" } }
                    .getOrDefault(emptyList())
                    .forEach { hits += Hit(it.repoFullName, it.number, it.kind.name, it.title, it.state) }
            }
        }
        val q = query.lowercase()
        return hits.filter { matches(it, q) }
            .distinctBy { "${it.repo.lowercase()}#${it.number}" }
            .sortedWith(compareBy<Hit>({ it.repo.lowercase() }, { it.number }))
            .take(LIMIT)
    }

    private fun matches(hit: Hit, q: String): Boolean =
        q.isEmpty() || hit.number.toString().startsWith(q) || hit.title.lowercase().contains(q) ||
            hit.repo.lowercase().contains(q) || hit.kind.lowercase().contains(q)

    /** 레포의 최근 Issue·PR. 실패는 빈 목록이고 캐시하지 않는다(다음 입력에서 다시 시도). */
    private fun recent(repo: GithubRepository): List<Hit> {
        recentCache.getIfPresent(repo.repoId)?.let { return it }
        val owner = repo.fullName.substringBefore('/')
        val name = repo.fullName.substringAfter('/')
        val items = try {
            githubApi.listRecentItems(repo.installationId, owner, name, RECENT_PER_KIND)
        } catch (e: GithubApiException) {
            log.warn { "github reference recent list failed: repo=${repo.fullName} cause=${e.message}" }
            return emptyList()
        }.map { Hit(it.repoFullName, it.number, it.kind.name, it.title, it.state) }
        recentCache.put(repo.repoId, items)
        return items
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
        private const val MAX_LOOKUP_REPOS = 10
        private const val RECENT_PER_KIND = 100
        private val NUMBER = Regex("^[1-9]\\d{0,8}$")
        private val CACHE_TTL: Duration = Duration.ofSeconds(30)
        private val RECENT_TTL: Duration = Duration.ofSeconds(60)
    }
}
