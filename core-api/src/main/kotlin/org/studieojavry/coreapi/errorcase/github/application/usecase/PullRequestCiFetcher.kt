package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary

/**
 * 연결·소급 부착 시점의 CI 조회 — head 커밋 검사 결과, 없으면(머지된 PR 만) 머지 커밋 검사 결과("머지 후 CI").
 * push 로만 CI 를 돌리는 레포는 PR head 에 검사가 하나도 없고 머지 커밋에만 있다.
 *
 * CI 는 부가 정보 — 조회 실패해도 연결은 진행(null). 이후 결과는 check_suite 웹훅([CheckSuiteWebhookHandler])이 채운다.
 */
@Component
@ConditionalOnGithubEnabled
class PullRequestCiFetcher(
    private val githubApi: GithubAppApiPort,
) {
    private val log = KotlinLogging.logger {}

    fun fetch(installationId: Long, owner: String, name: String, pr: GithubPullRequestSnapshot): CiSummary? =
        runCatching {
            val head = pr.headSha.takeIf { it.isNotBlank() }?.let { summary(installationId, owner, name, it) }
            head ?: pr.mergeCommitSha?.takeIf { pr.isMerged }?.let { summary(installationId, owner, name, it) }
        }
            .onFailure { log.warn { "github ci fetch failed: repo=$owner/$name pr=${pr.number} cause=${it.message}" } }
            .getOrNull()

    private fun summary(installationId: Long, owner: String, name: String, sha: String): CiSummary? =
        CiSummary.of(sha, githubApi.fetchCommitCheckRuns(installationId, owner, name, sha))
}
