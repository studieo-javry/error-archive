package org.studieojavry.coreapi.errorcase.github.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubRateLimitTracker
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxJpaRepository
import java.time.Duration
import java.time.Instant

/**
 * GitHub 연동 운영 지표 — Prometheus 로 수집해 Grafana 알림에 쓴다.
 *
 *  - `github.webhook.inbox.pending` / `.failed` : 받은 편지함 상태별 건수(scrape 마다 DB 조회, OutboxMetrics 와 같은 방식)
 *  - `github.webhook.inbox.pending.oldest_age`  : 가장 오래 기다린 PENDING 의 나이(초). 0 = 없음
 *  - `github.api.rate_limit.remaining` / `.limit` {installation, resource} : 설치별 호출 한도 — 리셋 전인 창만
 *
 * 처리 결과 카운터는 [GithubWebhookInboxProcessor][org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor],
 * 재동기화 지표는 [GithubReconcileScheduler][org.studieojavry.coreapi.errorcase.github.infrastructure.reconcile.GithubReconcileScheduler] 에 있다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubSyncMetrics(
    private val inboxRepo: GithubWebhookInboxJpaRepository,
    private val rateLimits: GithubRateLimitTracker,
    private val meterRegistry: MeterRegistry,
) {
    private val remaining = MultiGauge.builder(METRIC_RATE_REMAINING).register(meterRegistry)
    private val limit = MultiGauge.builder(METRIC_RATE_LIMIT).register(meterRegistry)

    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge(METRIC_PENDING, this) { it.inboxRepo.countByStatus(InboxStatus.PENDING).toDouble() }
        meterRegistry.gauge(METRIC_FAILED, this) { it.inboxRepo.countByStatus(InboxStatus.FAILED).toDouble() }
        meterRegistry.gauge(METRIC_PENDING_OLDEST_AGE, this) {
            val oldest = it.inboxRepo.oldestReceivedAt(InboxStatus.PENDING) ?: return@gauge 0.0
            Duration.between(oldest, Instant.now()).seconds.toDouble()
        }
    }

    /** 한도 창은 메모리에만 있어 주기적으로 옮겨 적는다. 리셋이 지난 창은 빠진다(overwrite). */
    @Scheduled(fixedDelayString = "\${core.github.metrics.rate-limit-refresh-ms:15000}")
    fun refreshRateLimits() {
        val windows = rateLimits.snapshot()
        fun rows(value: (GithubRateLimitTracker.Window) -> Int) = windows.map { (key, window) ->
            MultiGauge.Row.of(Tags.of("installation", key.first.toString(), "resource", key.second), value(window))
        }
        remaining.register(rows { it.remaining }, true)
        limit.register(rows { it.limit }, true)
    }

    companion object {
        const val METRIC_PENDING = "github.webhook.inbox.pending"
        const val METRIC_FAILED = "github.webhook.inbox.failed"
        const val METRIC_PENDING_OLDEST_AGE = "github.webhook.inbox.pending.oldest_age"
        const val METRIC_RATE_REMAINING = "github.api.rate_limit.remaining"
        const val METRIC_RATE_LIMIT = "github.api.rate_limit.limit"
    }
}
