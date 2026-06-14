package org.studieojavry.insightapi.activity.application.port

import java.time.ZoneId

/**
 * iam-api 로부터 사용자 timezone 조회. 실패/타임아웃 시 UTC 반환 (조용한 fallback).
 *
 * 적당한 캐시(1~5분) 는 adapter 측에서 처리.
 */
interface UserTimezonePort {
    fun fetch(userId: Long): ZoneId
}
