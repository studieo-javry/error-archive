package org.studieojavry.insightapi.activity.domain

/**
 * 활동의 출처. 발행 측(core-api 등)이 메시지에 실어 보낸다(없으면 USER 로 간주).
 *
 *  - USER: 사람이 직접 한 활동 — 평소처럼 잔디/KPI(insight_activity_daily) 집계.
 *  - AGENT_IMPORT: 에이전트가 수집한 초안을 반영해 생성된 활동 — raw 원장(insight_activity_event)엔
 *    적재하되(감사·정책 변경 시 rebuild), daily 집계에서는 **제외**한다(origin 게이트).
 *
 * raw 에 보존하는 이유: daily 는 raw 를 다시 읽어 truncate-and-rebuild 하므로, 게이트 판단에 쓰인
 * origin 이 raw 에 남아 있어야 재구성에서도 동일하게 제외된다.
 */
enum class ActivityOrigin {
    USER,
    AGENT_IMPORT,
    ;

    companion object {
        /** 메시지의 origin 문자열(없거나 미지의 값)은 USER 로 안전 폴백. */
        fun fromCodeOrUser(code: String?): ActivityOrigin =
            code?.let { runCatching { valueOf(it) }.getOrNull() } ?: USER
    }
}
