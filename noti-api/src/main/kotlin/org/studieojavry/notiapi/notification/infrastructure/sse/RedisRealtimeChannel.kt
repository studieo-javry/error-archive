package org.studieojavry.notiapi.notification.infrastructure.sse

/**
 * Redis pub/sub 노드 간 실시간 fan-out 의 **wire 계약**.
 *
 * 단일 채널 + 페이로드 내부 userId 방식을 쓴다(= per-user 채널 아님). 이유:
 *  - noti 노드 수가 작고(스케일아웃해도 수 개) 알림 발생률이 높지 않아, 모든 노드가 한 채널을
 *    구독하고 자기와 무관한 userId 는 "로컬 emitter 없음"으로 **O(1) 무시**하는 비용이 무시할 만함.
 *  - per-user 채널은 접속/해제마다 (un)subscribe 가 필요해 운영이 복잡하다. 단일 채널은 관리가 단순.
 *
 * 메시지는 [RealtimeEnvelope] 를 JSON 문자열로 직렬화한 것. `data` 는 이미 직렬화된 SSE 본문(JSON)
 * 이라 발생 노드/수신 노드가 **동일한 wire 포맷**을 보장한다(= [NotificationSseBroker.buildWirePayload]).
 */
object RedisRealtimeChannel {
    const val NAME = "noti:realtime"
}

/**
 * 채널 메시지 봉투. `data` 는 FE EventSource 가 받을 최종 JSON(문자열) 그대로.
 */
data class RealtimeEnvelope(
    val userId: Long,
    val eventId: String,
    val data: String,
)
