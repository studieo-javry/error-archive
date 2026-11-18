package org.studieojavry.coreapi.errorcase.eventevidence.domain.model

import java.time.Instant

/**
 * 수집 근거 Case 반영(PR-2). 사용자가 검토에서 선택한, Capture 에 연결된 Hook 이벤트(CaptureEvent)를 ErrorCase 에
 * 귀속시킨 **독립 스냅샷**. 원본 capture_event 의 생명주기와 분리(원본 GC 돼도 Case 근거는 보존).
 *
 * 원시 관측 이벤트는 AI 가 구조화한 기록(Step/Solution)과 성격이 다르므로 별도 모델로 보존하고,
 * 노출은 visibilityPolicy 로 통제한다(현재 소유자 전용 고정 — 발행/공유 자동 노출 금지).
 */
data class CaseEventEvidence(
    val id: Long? = null,
    val errorCaseId: Long,
    /** 원본 capture_event.id — 출처 추적용(FK 아님). */
    val sourceCaptureEventId: Long,
    val sourceClientEventId: String,
    val eventType: String,
    val observedAt: Instant,
    val receivedAt: Instant,
    /** 마스킹된 payload(jsonb 직렬화 String) 스냅샷. */
    val redactedPayload: String,
    val truncated: Boolean,
    val reviewedByUserId: Long,
    val reviewedAt: Instant,
    val visibilityPolicy: String = OWNER_ONLY,
) {
    companion object {
        const val OWNER_ONLY = "OWNER_ONLY"
    }
}
