package org.studieojavry.coreapi.errorcase.case.application.port

/**
 * draft-suggestion(LLM) 비용·남용 방어 경계.
 *
 * 두 가지 관심사:
 *  - **사용자별 쿼터/버스트**([checkUser]) — 한 계정의 과다 호출 차단. 초과면 요청을 **거절**(429).
 *  - **전역 일일 예산**([tryConsumeGlobalBudget]) — 서비스 전체 LLM 호출 상한. 소진되면 요청은 막지 않고
 *    **LLM 만 생략**(정규식 폴백, degraded). 사용자 경험은 유지하되 비용 폭주를 끊는다.
 *
 * 구현은 Redis 공유 카운터(멀티 파드 일관) — [org.studieojavry.coreapi.errorcase.case.infrastructure.ratelimit].
 */
interface DraftRateLimiterPort {

    /** 사용자별 버스트/일일 쿼터 검사 + 카운트 증가. 허용이면 allowed=true. */
    fun checkUser(userId: Long): Decision

    /** 전역 일일 예산을 1 소비 시도. 예산 내면 true, 소진이면 false(LLM 생략). 상한 미설정(≤0)이면 항상 true. */
    fun tryConsumeGlobalBudget(): Boolean

    data class Decision(
        val allowed: Boolean,
        /** 429 응답의 Retry-After(초). allowed=false 일 때만 의미. */
        val retryAfterSeconds: Long = 0,
        /** 거절 사유(per-minute / per-day). 로깅·응답 코드용. */
        val reason: String? = null,
    )
}
