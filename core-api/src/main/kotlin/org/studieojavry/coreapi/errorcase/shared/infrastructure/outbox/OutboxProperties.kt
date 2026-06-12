package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox / Relayer 동작 파라미터.
 *
 * 폴링 전략 = **adaptive**: pending 발견 시 [busyDelayMs] 로 빠르게, 빈 결과 시 점진 backoff → [idleMaxDelayMs] 까지.
 *
 * 비활성화: `noti.publisher.mode != kafka` 이면 OutboxAdapter / Relayer 모두 빈 등록 안 됨.
 */
@ConfigurationProperties(prefix = "core.outbox")
data class OutboxProperties(
    /** pending 발견 시 다음 라운드 지연. burst 흐름의 latency 결정. */
    val busyDelayMs: Long = 200,
    /** 첫 idle 라운드 지연. */
    val idleInitialDelayMs: Long = 1000,
    /** idle backoff cap. */
    val idleMaxDelayMs: Long = 5000,
    /** 빈 결과마다 다음 지연을 N 배 (1.0 = 일정 간격). */
    val idleMultiplier: Double = 2.0,
    /** 한 라운드에 처리할 최대 row 수. */
    val batchSize: Int = 50,
    /** 누적 실패가 이 횟수 도달 → status=DEAD, 자동 재시도 중단. */
    val maxAttempts: Int = 10,
    /** retry backoff = min(2^attempts * baseBackoffMs, maxBackoffMs). */
    val baseBackoffMs: Long = 5000,
    val maxBackoffMs: Long = 3600000,
    /** SENT row 보관 일수 — 이후 cleanup 잡이 DELETE. */
    val sentTtlDays: Long = 30,
)
