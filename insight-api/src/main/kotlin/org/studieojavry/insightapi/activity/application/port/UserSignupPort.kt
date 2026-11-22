package org.studieojavry.insightapi.activity.application.port

import java.time.Instant

/**
 * iam-api 로부터 "최근 가입 코호트"(비게스트)를 조회.
 *
 * 활성화 지표(가입 → 첫 케이스 지연/활성화율) 계산에만 쓰인다. 실패/타임아웃 시 빈 목록 반환
 * (활성화 지표만 조용히 건너뛰고, 원장 기반 다른 지표는 계속 계산) — 지표 집계가 외부 의존성으로 멈추지 않게.
 */
interface UserSignupPort {

    /** [since] 이후 가입한 비게스트 사용자를 [limit] 만큼. 실패 시 빈 목록. */
    fun recentSignups(since: Instant, limit: Int): List<Signup>

    data class Signup(val userId: Long, val createdAt: Instant)
}
