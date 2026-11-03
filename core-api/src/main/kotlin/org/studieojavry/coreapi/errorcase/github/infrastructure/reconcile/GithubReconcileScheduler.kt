package org.studieojavry.coreapi.errorcase.github.infrastructure.reconcile

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEvidenceReconciler
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 놓친 웹훅 재동기화 주기 실행 — 다중 인스턴스 중 하나만([SchedulerLock]).
 * `core.github.reconcile.enabled=false` 면 등록하지 않는다(테스트 등). 운영자는 `/internal/admin/github/reconcile` 로 바로 돌릴 수 있다.
 *
 * 지표: `github.reconcile.items{result}`(checked·failed·skipped·deferred 누적), `github.reconcile.last_run`(마지막으로 끝난 시각, epoch 초 — 멈춤 감지).
 * 마지막 실행 시각은 이 인스턴스가 돈 것만 안다(락을 못 잡은 인스턴스는 0 유지 → 대시보드·알림은 max 로 본다).
 */
@Component
@ConditionalOnGithubEnabled
@ConditionalOnProperty(prefix = "core.github.reconcile", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GithubReconcileScheduler(
    private val reconciler: GithubEvidenceReconciler,
    private val meterRegistry: MeterRegistry,
) {
    private val lastRun = AtomicLong(0).also { meterRegistry.gauge(METRIC_LAST_RUN, it) }

    @Scheduled(
        initialDelayString = "\${core.github.reconcile.initial-delay-ms:120000}",
        fixedDelayString = "\${core.github.reconcile.poll-delay-ms:900000}",
    )
    @SchedulerLock(name = "github-evidence-reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun tick() {
        val result = reconciler.reconcileDue()
        count("checked", result.checked)
        count("failed", result.failed)
        count("skipped", result.skipped)
        count("deferred", result.deferred)
        lastRun.set(Instant.now().epochSecond)
    }

    private fun count(result: String, n: Int) {
        meterRegistry.counter(METRIC_ITEMS, "result", result).increment(n.toDouble())
    }

    companion object {
        const val METRIC_ITEMS = "github.reconcile.items"
        const val METRIC_LAST_RUN = "github.reconcile.last_run"
    }
}
