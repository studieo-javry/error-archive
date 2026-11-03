package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ReconcileTarget
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubRateLimitPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import java.time.Instant

/**
 * 놓친 웹훅 재동기화 — GitHub 는 전달에 실패한 웹훅(우리 서버가 내려가 있던 동안 등)을 자동으로 다시 보내지 않는다.
 * 그래서 Case 에 붙은 Issue/PR 중 아직 바뀔 수 있는 것(열림·CI 진행 중·최근 바뀜)을 주기적으로 다시 조회해 웹훅과 같은 처리를 한다.
 *
 *  - PR: [PullRequestWebhookHandler.reconcile] — 스냅샷·장부·자동 부착·상태 기록 + 끝나지 않은 CI 재조회 + 닫힘→열림 감지
 *  - Issue: [IssueWebhookHandler.reconcile] — 스냅샷·열림↔닫힘 기록. 열린 Issue 는 소급 부착([IssuePullRequestBackfiller])도 다시 돌려
 *    놓친 PR 웹훅으로 못 붙은 "이 Issue 를 닫는 PR" 을 붙인다
 *
 * 처리는 웹훅과 같아 멱등(타임라인 dedupe·스냅샷은 더 새로울 때만) — 웹훅을 놓치지 않았으면 아무 일도 생기지 않는다.
 * 백그라운드 작업이라 GitHub 호출 한도 예비분(사용자 몫)에 닿은 설치는 이번 회차에서 건너뛴다([GithubRateLimitPort]).
 * 대상 하나가 실패해도 나머지는 계속하고, 확인 시각을 남겨 같은 대상이 매 회차 앞자리를 차지하지 않게 한다(한도 초과는 남기지 않음 — 다음 회차에 바로).
 */
@Component
@ConditionalOnGithubEnabled
class GithubEvidenceReconciler(
    private val installations: GithubInstallationRepositoryPort,
    private val evidence: GithubEvidenceRepositoryPort,
    private val pullRequests: PullRequestWebhookHandler,
    private val issues: IssueWebhookHandler,
    private val backfiller: IssuePullRequestBackfiller,
    private val errorCases: ErrorCaseRepositoryPort,
    private val rateLimits: GithubRateLimitPort,
    private val properties: GithubAppProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = KotlinLogging.logger {}
    private val tx = TransactionTemplate(transactionManager)

    /**
     * [checked] 다시 조회해 맞춘 대상, [failed] GitHub 오류 등으로 실패(다음 주기에 다시), [skipped] 저장소·설치가 비활성이라 건너뜀,
     * [deferred] 호출 한도 때문에 이번엔 미룬 대상.
     */
    data class Result(val checked: Int, val failed: Int, val skipped: Int, val deferred: Int)

    fun reconcileDue(limit: Int = properties.reconcile.batchSize, now: Instant = Instant.now()): Result {
        val config = properties.reconcile
        val due = evidence.findReconcileDue(now.minus(config.minInterval), now.minus(config.recentWindow), limit.coerceIn(1, 500))
        var checked = 0
        var failed = 0
        var skipped = 0
        var deferred = 0
        val heldInstallations = mutableSetOf<Long>()
        for (target in due) {
            val repo = installations.findRepository(target.ref.repoId)?.takeIf { it.isActive }
            val installation = repo?.let { installations.findById(it.installationId) }?.takeIf { it.status == GithubInstallationStatus.ACTIVE }
            if (repo == null || installation == null) {
                skipped++
                mark(target)
                continue
            }
            val id = installation.installationId
            if (id in heldInstallations || rateLimits.backgroundWait(id) != null) {
                heldInstallations += id
                deferred++
                continue
            }
            try {
                reconcileOne(target, installation, repo)
                checked++
            } catch (ex: Exception) {
                if (generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<GithubApiException>().any { it.retryAfter != null }) {
                    heldInstallations += id
                    deferred++
                    continue
                }
                failed++
                log.warn { "[github] reconcile failed: ${target.kind} ${repo.fullName}#${target.ref.number} cause=${ex.message}" }
            }
            mark(target)
        }
        if (due.isNotEmpty()) log.info { "[github] reconcile due=${due.size} checked=$checked failed=$failed skipped=$skipped deferred=$deferred" }
        return Result(checked, failed, skipped, deferred)
    }

    private fun reconcileOne(target: ReconcileTarget, installation: GithubInstallation, repo: GithubRepository) {
        when (target.kind) {
            GithubRefKind.PULL_REQUEST -> tx.executeWithoutResult { pullRequests.reconcile(installation, repo, target.ref.number) }
            GithubRefKind.ISSUE -> {
                val item = tx.execute { issues.reconcile(installation, repo, target.ref.number) } ?: return
                if (item.snapshot.state != GithubIssueSnapshot.STATE_OPEN) return
                // 소급 부착은 후보마다 GitHub 를 부르므로 트랜잭션 밖에서(자기 트랜잭션을 짧게 연다) — 연결 API 와 같은 방식
                for (caseId in evidence.findActiveLinkedCaseIds(GithubRefKind.ISSUE, target.ref)) {
                    val errorCase = errorCases.findById(caseId) ?: continue
                    backfiller.backfill(errorCase, target.ref, item.pullRequestCandidates)
                }
            }
        }
    }

    private fun mark(target: ReconcileTarget) {
        tx.executeWithoutResult { evidence.markReconciled(target, Instant.now()) }
    }
}
