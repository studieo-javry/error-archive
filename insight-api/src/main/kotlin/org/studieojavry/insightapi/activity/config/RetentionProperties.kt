package org.studieojavry.insightapi.activity.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * insight 데이터 보존 정책.
 *
 * raw event = 잔디(12개월) + 월말 정책 변경/rebuild 여유 1개월 = **13개월** default.
 * daily 집계 row 는 *영구 보존* (column 수 적어 부담 없고, 1년 전 잔디 조회 등 backfill 필요시 사용).
 *
 * cleanup 잡이 새벽 03:00 KST 에 `created_at < now() - eventTtlDays` 인 raw 를 DELETE.
 */
@ConfigurationProperties(prefix = "insight.retention")
data class RetentionProperties(
    val eventTtlDays: Long = 395,   // 13개월 (365 + 30)
    val deleteBatchSize: Int = 5000,
)
