package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * inbox 에 쌓인 웹훅 1건 처리 — 호출자(inbox 처리기)의 트랜잭션 안에서 실행된다.
 *
 * 설치/레포 동기화(P0) + PR 자동 부착(P1-c).
 *  - `installation`            : deleted / suspend / unsuspend → 설치 상태
 *  - `installation_repositories`: added / removed → 설치 대상 레포
 *  - `repository`              : renamed / transferred / privatized / publicized / deleted → 레포 메타(is_private 가 노출 정책 기준)
 *  - `pull_request`            : [PullRequestWebhookHandler] — 닫는 PR 자동 부착·상태 기록 (P1-c)
 *  - `ping` 및 그 외(issues / check_suite 는 P1-d) → SKIPPED
 *
 * 우리 쪽에 연결되지 않은 설치의 이벤트는 SKIPPED — 연결은 반드시 [CompleteGithubInstallationUseCase] 로만.
 */
@Component
@ConditionalOnGithubEnabled
class ProcessGithubWebhookEventUseCase(
    private val repository: GithubInstallationRepositoryPort,
    private val objectMapper: ObjectMapper,
    private val pullRequests: PullRequestWebhookHandler,
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
            else -> Result.SKIPPED
        }
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
        return Result.DONE
    }
}

/** 배열 노드 → 원소 리스트 (배열이 아니거나 없으면 빈 리스트). */
private fun JsonNode.items(): List<JsonNode> = if (isArray) (0 until size()).map { get(it) } else emptyList()
