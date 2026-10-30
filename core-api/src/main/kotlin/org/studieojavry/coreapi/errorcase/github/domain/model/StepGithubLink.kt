package org.studieojavry.coreapi.errorcase.github.domain.model

import java.time.Instant

/**
 * Step ↔ GitHub 근거 꼬리표. [linkId] 는 같은 Case 의 [CaseGithubLink] — Case 연결이 원본이라
 * 범위 검사·비공개 가림은 Case 연결 규칙을 그대로 따른다. 근거 하나가 여러 Step 에, Step 하나에 여러 근거가 붙을 수 있다.
 */
data class StepGithubLink(
    val stepId: Long,
    val linkId: Long,
    val caseId: Long,
    val source: Source,
    val createdByUserId: Long?,
    val createdAt: Instant,
) {
    /** MANUAL = 사용자가 Step 에서 직접 연결, SUGGESTION = PR → Step 제안 수락(P1-e B). */
    enum class Source { MANUAL, SUGGESTION }
}
