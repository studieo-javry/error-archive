package org.studieojavry.coreapi.errorcase.github.application.usecase

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Duration
import java.util.Optional

/**
 * Case 본문에 적힌 GitHub 참조(`owner/repo#N`) → 읽기 화면에 띄울 제목·종류·상태.
 *
 * 참조는 **글 속 인용**일 뿐 Case 근거가 아니다 — 저장도, 타임라인 기록도 하지 않는다.
 *  - 레포가 그 Case 공간에 연결돼 있지 않으면 `resolved=false` (본문엔 적혀 있으니 링크 모양은 FE 가 그대로 둔다)
 *  - 비공개 레포인데 요청자가 비공개 근거를 못 보면(근거 가림과 같은 기준) `masked=true` — 제목·상태를 주지 않고 GitHub 도 부르지 않는다
 *  - GitHub 에 없거나 호출 실패 → `resolved=false`
 *
 * 같은 Issue·PR 을 여러 사람이 열어 보는 일이 잦아 [CACHE_TTL] 동안 조회 결과를 재사용한다.
 */
@Service
@ConditionalOnGithubEnabled
class ResolveGithubReferencesUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val policy: GithubCaseAccessPolicy,
    private val scopeGuard: GithubScopeGuard,
    private val githubApi: GithubAppApiPort,
) {
    private val log = KotlinLogging.logger {}

    data class Reference(
        val ref: String,
        val repo: String,
        val number: Int,
        val resolved: Boolean,
        val masked: Boolean = false,
        val kind: String? = null,
        val title: String? = null,
        val status: String? = null,
        val url: String? = null,
    )

    private data class Found(val kind: String, val title: String, val status: String, val url: String)

    private val cache: Cache<String, Optional<Found>> = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL).maximumSize(5_000).build()

    fun invoke(caseId: Long, viewerUserId: Long, refs: List<String>): List<Reference> {
        val errorCase = errorCases.findById(caseId) ?: throw ErrorCaseNotFoundException(caseId)
        access.requireRead(errorCase, viewerUserId)
        val canSeePrivate = policy.canSeePrivate(errorCase, viewerUserId)

        return refs.map { it.trim() }.distinct().take(MAX_REFS).mapNotNull { raw ->
            val m = REF.matchEntire(raw) ?: return@mapNotNull null
            val repoName = m.groupValues[1]
            val number = m.groupValues[2].toInt()
            val unresolved = Reference(raw, repoName, number, resolved = false)

            val resolved = runCatching { scopeGuard.resolve(repoName) }.getOrNull() ?: return@mapNotNull unresolved
            if (!scopeGuard.isSameScope(resolved.installation, errorCase)) return@mapNotNull unresolved
            if (!policy.isVisible(resolved.repository, canSeePrivate)) return@mapNotNull unresolved.copy(masked = true)

            val found = lookup(resolved.installation.installationId, resolved.repository.fullName, number)
                ?: return@mapNotNull unresolved
            Reference(raw, resolved.repository.fullName, number, true, false, found.kind, found.title, found.status, found.url)
        }
    }

    private fun lookup(installationId: Long, fullName: String, number: Int): Found? {
        val key = "${fullName.lowercase()}#$number"
        cache.getIfPresent(key)?.let { return it.orElse(null) }
        val item = try {
            githubApi.fetchItem(installationId, fullName.substringBefore('/'), fullName.substringAfter('/'), number)
        } catch (e: GithubApiException) {
            // 일시 장애는 캐시하지 않는다 — 다음 열람 때 다시 시도.
            log.warn { "github reference resolve failed: $key cause=${e.message}" }
            return null
        }
        val found = when (item) {
            is GithubAppApiPort.IssueItem -> Found("ISSUE", item.snapshot.title, item.snapshot.state, item.snapshot.url)
            is GithubAppApiPort.PullRequestItem -> Found("PULL_REQUEST", item.snapshot.title, item.snapshot.state, item.snapshot.url)
            null -> null
        }
        cache.put(key, Optional.ofNullable(found))
        return found
    }

    companion object {
        /** FE 렌더러와 같은 모양만 — `owner/repo#N`. 짧은 `#N` 은 저장소가 정해지지 않아 참조가 아니다. */
        private val REF = Regex("^([A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100})#([1-9]\\d{0,8})$")
        const val MAX_REFS = 50
        private val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}
