package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant

/**
 * 검토 반영(import) 기록 — Capture 당 최대 1건(이슈 #144 §2 CaptureImport).
 *
 *  - requestHash: 정규화한 반영 요청(reviewedRevision + 선택 항목 + markResolved)의 해시.
 *    재반영이 같은 해시면 같은 케이스를 멱등 반환, 다른 해시면 409 CAPTURE_ALREADY_IMPORTED.
 *  - attemptStepMap: clientAttemptId → 생성된 stepId(출처·매핑 보존).
 */
data class CaptureImport(
    val id: Long? = null,
    val captureId: Long,
    val reviewedRevision: Int,
    val reviewerUserId: Long,
    val reviewedAt: Instant,
    val targetCaseId: Long,
    val requestHash: String,
    val attemptStepMap: Map<String, Long>,
    val createdAt: Instant = reviewedAt,
)
