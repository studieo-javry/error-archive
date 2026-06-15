package org.studieojavry.insightapi.activity.domain

/**
 * 잔디 가중치를 결정하는 사용자 활동 종류.
 *
 * 가중치는 application.yml ( insight.activity.weights.{TYPE} ) 에서 주입
 * — 정책 변경 시 raw 이벤트의 score 컬럼은 publish 시점 값 으로 보존되고, daily 만 재계산.
 *
 * 새 활동 추가 시: enum + yml weights + 출처 서비스의 publisher 한 곳을 동시에 갱신.
 */
enum class ActivityType {
    CASE_CREATED,
    CASE_RESOLVED,
    CASE_PUBLISHED,
    STEP_ADDED,
    SOLUTION_ADDED,
    COMMENT_POSTED,
    COMMENT_REACTION,
    COMMENT_HELPFUL,
    FOLLOWED_USER,
    ;

    companion object {
        fun fromCodeOrNull(code: String): ActivityType? =
            runCatching { valueOf(code) }.getOrNull()
    }
}
