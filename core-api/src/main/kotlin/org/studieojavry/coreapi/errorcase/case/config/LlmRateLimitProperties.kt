package org.studieojavry.coreapi.errorcase.case.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * draft-suggestion(LLM) 비용 가드 설정. prefix `llm.rate-limit`, `@ConfigurationPropertiesScan` 으로 자동 등록.
 *
 * - `enabled=false` 면 Redis 리미터 대신 NoOp(항상 허용) — Redis 없는 test 프로필에서 사용.
 * - 한도는 환경별 overlay 에서 조정(데모 플래그처럼 서비스별 env).
 */
@ConfigurationProperties("llm.rate-limit")
data class LlmRateLimitProperties(
    /** false 면 NoOp 리미터(무제한). 기본 true(Redis 리미터). */
    val enabled: Boolean = true,
    /** 사용자별 분당 호출 상한(버스트). */
    val perMinute: Int = 5,
    /** 사용자별 일일 호출 상한(쿼터). */
    val perDay: Int = 50,
    /** 서비스 전역 일일 LLM 호출 상한(예산 차단밸브). 0 이하면 미적용(무제한). */
    val globalPerDay: Int = 0,
)
