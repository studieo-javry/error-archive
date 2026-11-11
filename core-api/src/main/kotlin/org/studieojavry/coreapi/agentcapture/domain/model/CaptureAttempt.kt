package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant

/**
 * 하나의 의미 있는 시도(조사·수정·검증). captureId 로 Capture 를 참조하는 별도 집합체.
 * clientAttemptId 는 같은 Capture 안에서 유일(엔티티 UNIQUE 제약) — 멱등 재전송에서 중복 생성 방지.
 */
class CaptureAttempt private constructor(
    val id: Long?,
    val captureId: Long,
    val clientAttemptId: String,
    var orderIndex: Int,
    var result: AttemptResult,
    var attemptType: String?,
    var detail: AttemptDetail,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    fun update(result: AttemptResult, attemptType: String?, detail: AttemptDetail, now: Instant) {
        this.result = result
        this.attemptType = attemptType
        this.detail = detail
        this.updatedAt = now
    }

    companion object {
        fun create(
            captureId: Long,
            clientAttemptId: String,
            orderIndex: Int,
            result: AttemptResult,
            attemptType: String?,
            detail: AttemptDetail,
            now: Instant,
        ): CaptureAttempt = CaptureAttempt(
            id = null,
            captureId = captureId,
            clientAttemptId = clientAttemptId,
            orderIndex = orderIndex,
            result = result,
            attemptType = attemptType,
            detail = detail,
            createdAt = now,
            updatedAt = now,
        )

        fun reconstitute(
            id: Long,
            captureId: Long,
            clientAttemptId: String,
            orderIndex: Int,
            result: AttemptResult,
            attemptType: String?,
            detail: AttemptDetail,
            createdAt: Instant,
            updatedAt: Instant,
        ): CaptureAttempt = CaptureAttempt(
            id, captureId, clientAttemptId, orderIndex, result, attemptType, detail, createdAt, updatedAt,
        )
    }
}
