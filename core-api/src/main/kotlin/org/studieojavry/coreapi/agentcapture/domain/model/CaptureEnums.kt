package org.studieojavry.coreapi.agentcapture.domain.model

/**
 * Capture 수명주기. OPEN → READY_FOR_REVIEW → IMPORTED, 또는 OPEN/READY 에서 DISCARDED.
 * IMPORTED·DISCARDED 는 종료 상태(P0 에서 되돌리지 않음).
 *
 * ⚠️ 기록 종료(complete)는 '문제 해결'을 의미하지 않는다. 미해결 초안도 봉인·검토할 수 있다.
 */
enum class CaptureStatus {
    OPEN,
    READY_FOR_REVIEW,
    IMPORTED,
    DISCARDED,
    ;

    val isTerminal: Boolean get() = this == IMPORTED || this == DISCARDED

    /** 에이전트가 쓸 수 있는 상태(OPEN 에서만). READY 부터는 사용자 검토만 허용. */
    val acceptsAgentWrites: Boolean get() = this == OPEN
}

/** Attempt 결과. 원래 값은 반영 시 Step 상태로 매핑되며 출처에도 보존된다. */
enum class AttemptResult {
    PENDING,
    SUCCEEDED,
    FAILED,
    INCONCLUSIVE,
}

/** 검증 결과. PASSED 는 '지정된 범위에서 관측한 결과'일 뿐 운영 전체 해결을 보증하지 않는다. */
enum class VerificationResult {
    NOT_RUN,
    PASSED,
    FAILED,
    INCONCLUSIVE,
}

/** 검증 범위. */
enum class VerificationScope {
    LOCAL,
    CI,
    MANUAL,
}
