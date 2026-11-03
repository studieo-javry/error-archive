package org.studieojavry.coreapi.errorcase.github.infrastructure.reconcile

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEvidenceReconciler
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled

/**
 * 놓친 웹훅 재동기화 주기 실행 — 다중 인스턴스 중 하나만([SchedulerLock]).
 * `core.github.reconcile.enabled=false` 면 등록하지 않는다(테스트 등). 운영자는 `/internal/admin/github/reconcile` 로 바로 돌릴 수 있다.
 */
@Component
@ConditionalOnGithubEnabled
@ConditionalOnProperty(prefix = "core.github.reconcile", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GithubReconcileScheduler(
    private val reconciler: GithubEvidenceReconciler,
) {
    @Scheduled(
        initialDelayString = "\${core.github.reconcile.initial-delay-ms:120000}",
        fixedDelayString = "\${core.github.reconcile.poll-delay-ms:900000}",
    )
    @SchedulerLock(name = "github-evidence-reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun tick() {
        reconciler.reconcileDue()
    }
}
