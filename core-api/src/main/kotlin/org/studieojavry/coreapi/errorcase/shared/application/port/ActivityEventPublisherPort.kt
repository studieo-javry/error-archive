package org.studieojavry.coreapi.errorcase.shared.application.port

import java.time.Instant

/**
 * 사용자 활동 이벤트 (잔디용) 발행.
 *
 * core-api 의 도메인 이벤트(케이스 생성·step 추가·솔루션·댓글·리액션·도움됨)가 발생할 때마다 호출.
 * insight-api 의 `user-activity.v1` 토픽 consumer 가 받아 raw 적재 + daily UPSERT.
 *
 * **fire-and-forget**: 실패는 silently swallow + log warn. 잔디 누락이 도메인 작업(케이스 작성 등)을
 * 막아선 안 됨. 추후 outbox/재처리 도입 여지.
 *
 * **score 미전송**: consumer 가 yml weight 정책을 적용. 정책 변경 시 publish 측 손대지 않음.
 */
interface ActivityEventPublisherPort {

    fun publish(event: ActivityEvent)

    /**
     * @param userId       활동의 주체(actor). 잔디 소유자.
     * @param type         활동 종류. insight-api 의 ActivityType 과 같은 문자열로 매핑됨.
     * @param occurredAt   도메인 이벤트 발생 시각 (DB 트랜잭션 커밋 이전이라도 의도된 시각).
     * @param idempotencyKey 중복 차단 키. 도메인 ID 로 자명 생성 (예: `case:42`, `react:7:101:THUMBS_UP`).
     * @param meta         consumer 가 그대로 jsonb 컬럼에 저장. nullable.
     */
    data class ActivityEvent(
        val userId: Long,
        val type: Type,
        val occurredAt: Instant,
        val idempotencyKey: String,
        val meta: Map<String, Any?>? = null,
    )

    /** core-api 가 발행하는 6종. insight-api 의 ActivityType enum 의 부분 집합. */
    enum class Type {
        CASE_CREATED,
        STEP_ADDED,
        SOLUTION_ADDED,
        COMMENT_POSTED,
        COMMENT_REACTION,
        COMMENT_HELPFUL,
    }
}
