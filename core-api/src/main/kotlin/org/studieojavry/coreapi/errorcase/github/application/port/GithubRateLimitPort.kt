package org.studieojavry.coreapi.errorcase.github.application.port

import java.time.Duration
import java.time.Instant

/**
 * GitHub 호출 한도 잔량 — 응답 헤더(`x-ratelimit-*`)로 설치·자원(core/graphql)별로 추적한다.
 * 한도는 설치 하나를 사용자 요청과 백그라운드 작업(웹훅 처리·재동기화)이 같이 쓰므로, 백그라운드가 먼저 물어보고 비켜 준다.
 */
interface GithubRateLimitPort {
    /**
     * 이 설치로 백그라운드 호출을 지금 해도 되나 — 어느 자원이든 남은 양이 예비분(사용자 몫) 이하면 리셋까지 기다릴 시간, 아니면 null.
     * 아직 응답을 한 번도 못 봤으면(재기동 직후 등) null.
     */
    fun backgroundWait(installationId: Long, now: Instant = Instant.now()): Duration?
}
