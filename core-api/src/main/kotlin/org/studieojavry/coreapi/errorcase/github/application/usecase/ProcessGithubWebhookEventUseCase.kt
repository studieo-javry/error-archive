package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * inbox 에 쌓인 웹훅 1건 처리 — 호출자(inbox 처리기)의 트랜잭션 안에서 실행된다.
 *
 * 설치/레포 동기화(P0) + PR 자동 부착(P1-c) + CI·Issue 상태(P1-d).
 *  - `installation`            : deleted / suspend / unsuspend → 설치 상태
 *  - `installation_repositories`: added / removed → 설치 대상 레포
 *  - `repository`              : renamed / transferred / privatized / publicized / deleted → 레포 메타(is_private 가 노출 정책 기준)
 *  - `pull_request`            : [PullRequestWebhookHandler] — 닫는 PR 자동 부착·상태 기록 (P1-c)
 *  - `check_suite`             : [CheckSuiteWebhookHandler] — 커밋 CI 결과(머지 커밋 포함) → PR CI 요약·타임라인 (P1-d)
 *  - `issues`                  : [IssueWebhookHandler] — 붙은 Issue 스냅샷·닫힘/다시 열림 (P1-d)
 *  - `github_app_authorization`: revoked → 그 GitHub 사용자의 저장 토큰 폐기(installation 없는 payload — sender.id 로 매칭)
 *  - `ping` 및 그 외 → SKIPPED
 *
 * 우리 쪽에 연결되지 않은 설치의 이벤트는 SKIPPED — 연결은 반드시 [CompleteGithubInstallationUseCase] 로만.
 */
@Component
@ConditionalOnGithubEnabled
class ProcessGithubWebhookEventUseCase(
    private val repository: GithubInstallationRepositoryPort,
    private val connections: GithubConnectionRepositoryPort,
    private val objectMapper: ObjectMapper,
    private val pullRequests: PullRequestWebhookHandler,
    private val checkSuites: CheckSuiteWebhookHandler,
    private val issues: IssueWebhookHandler,
    private val userAuthorizations: GithubUserAuthorizationPort,
) {
    private val log = KotlinLogging.logger {}

    enum class Result { DONE, SKIPPED }

    fun invoke(event: String, action: String?, installationId: Long?, payload: String): Result {
        val root = objectMapper.readTree(payload)
        return when (event) {
            "installation" -> onInstallation(action, installationId)
            "installation_repositories" -> onInstallationRepositories(action, installationId, root)
            "repository" -> onRepository(action, installationId, root)
            "pull_request" -> if (pullRequests.handle(action, installationId, root)) Result.DONE else Result.SKIPPED
            "check_suite" -> if (checkSuites.handle(action, installationId, root)) Result.DONE else Result.SKIPPED
            "issues" -> if (issues.handle(action, installationId, root)) Result.DONE else Result.SKIPPED
            "github_app_authorization" -> onAppAuthorization(action, root)
            else -> Result.SKIPPED
        }
    }

    /**
     * 사용자가 GitHub 설정에서 App 인가를 취소함(`github_app_authorization` action=revoked). payload 에 installation 이 없어
     * installationId 는 null 로 온다 — `sender.id`(GitHub 사용자 숫자 id)로 저장 토큰을 찾아 폐기한다.
     * 저장된 토큰이 없으면(우리가 교환한 적 없는 사용자) 할 일 없음 → SKIPPED.
     */
    private fun onAppAuthorization(action: String?, root: JsonNode): Result {
        if (action != "revoked") return Result.SKIPPED
        val githubUserId = root.path("sender").path("id").asLong()
        if (githubUserId == 0L) return Result.SKIPPED
        val purged = userAuthorizations.deleteByGithubUserId(githubUserId)
        log.info { "github app authorization revoked: githubUserId=$githubUserId purgedTokens=$purged" }
        return if (purged > 0) Result.DONE else Result.SKIPPED
    }

    private fun onInstallation(action: String?, installationId: Long?): Result {
        val installation = installationId?.let(repository::findById) ?: return Result.SKIPPED
        val next = when (action) {
            "deleted" -> GithubInstallationStatus.DELETED
            "suspend" -> GithubInstallationStatus.SUSPENDED
            "unsuspend" -> GithubInstallationStatus.ACTIVE
            else -> return Result.SKIPPED   // created 는 연결 API 가 담당, new_permissions_accepted 는 무시
        }
        installation.changeStatus(next)
        repository.save(installation)
        log.info { "github installation status: installation=$installationId action=$action → ${installation.status}" }
        return Result.DONE
    }

    private fun onInstallationRepositories(action: String?, installationId: Long?, root: JsonNode): Result {
        val installation = installationId?.let(repository::findById) ?: return Result.SKIPPED
        val changed = when (action) {
            "added" -> root.path("repositories_added").items().map { r ->
                val id = r.path("id").asLong()
                (repository.findRepository(id)
                    ?.also { it.attach(installation.installationId, r.path("full_name").asString(), r.path("private").asBoolean()) }
                    ?: GithubRepository.of(id, installation.installationId, r.path("full_name").asString(), r.path("private").asBoolean()))
            }
            "removed" -> root.path("repositories_removed").items().mapNotNull { r ->
                repository.findRepository(r.path("id").asLong())
                    ?.takeIf { it.installationId == installation.installationId }
                    ?.also { it.detach() }
            }
            else -> return Result.SKIPPED
        }
        repository.saveRepositories(changed)
        // 설치에 새로 허용된 레포는 "후보"로만 동기화한다 — 어느 공간의 선택(github_connection_repository)에도 자동 추가하지 않는다.
        // 멀티-공간에서 한 공간의 grant 확대가 다른 공간의 사용 레포로 새면 비공개 정보가 누출되므로(지시서 §6),
        // 각 공간은 refresh 후 명시적으로 선택해야 한다.
        // 반대로 grant 에서 빠진 레포는 모든 공간에서 ACCESS_LOST 로 — 재허용돼도 자동 복구되지 않게(재선택 필요).
        if (action == "removed" && changed.isNotEmpty()) {
            connections.markSelectionsAccessLost(changed.map { it.repoId }, Instant.now())
        }
        return Result.DONE
    }

    private fun onRepository(action: String?, installationId: Long?, root: JsonNode): Result {
        val r = root.path("repository")
        val repo = repository.findRepository(r.path("id").asLong()) ?: return Result.SKIPPED
        when (action) {
            "deleted" -> repo.detach()
            "renamed", "transferred", "privatized", "publicized", "edited" -> {
                val updatedAt = r.path("updated_at").takeIf { it.isString }?.asString()?.let(Instant::parse)
                val applied = repo.applyChange(r.path("full_name").asString(), r.path("private").asBoolean(), updatedAt)
                if (!applied) return Result.SKIPPED   // 이미 더 최신 값을 반영함(순서 뒤바뀐 옛 이벤트)
                // transfer 로 다른 설치(우리가 모르는 계정)로 옮겨졌으면 이 설치 대상에서는 빠진 것.
                if (action == "transferred" && installationId != null && installationId != repo.installationId) {
                    repository.findById(installationId)?.let { repo.attach(it.installationId, repo.fullName, repo.isPrivate) }
                        ?: repo.detach()
                }
            }
            else -> return Result.SKIPPED
        }
        repository.saveRepositories(listOf(repo))
        // 레포가 삭제/이전으로 이 설치 grant 에서 빠졌으면 그 선택을 ACCESS_LOST 로(재허용돼도 자동 복구 금지).
        if (action == "deleted" || !repo.isActive) {
            connections.markSelectionsAccessLost(listOf(repo.repoId), Instant.now())
        }
        return Result.DONE
    }
}

/** 배열 노드 → 원소 리스트 (배열이 아니거나 없으면 빈 리스트). */
private fun JsonNode.items(): List<JsonNode> = if (isArray) (0 until size()).map { get(it) } else emptyList()
