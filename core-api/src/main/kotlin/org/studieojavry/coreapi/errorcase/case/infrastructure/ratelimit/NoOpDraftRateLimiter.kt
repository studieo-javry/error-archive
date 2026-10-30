package org.studieojavry.coreapi.errorcase.case.infrastructure.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.DraftRateLimiterPort

/**
 * 무제한 리미터 — `llm.rate-limit.enabled=false` 일 때만 활성(Redis 없는 test 프로필·비용 가드 비활성 환경).
 * 항상 허용하고 전역 예산도 소진되지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "llm.rate-limit", name = ["enabled"], havingValue = "false")
class NoOpDraftRateLimiter : DraftRateLimiterPort {
    override fun checkUser(userId: Long) = DraftRateLimiterPort.Decision(true)
    override fun tryConsumeGlobalBudget() = true
}
