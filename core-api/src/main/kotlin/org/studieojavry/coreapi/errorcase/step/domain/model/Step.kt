package org.studieojavry.coreapi.errorcase.step.domain.model

import org.studieojavry.coreapi.errorcase.step.domain.model.vo.AttemptType
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.LocalDateTime

/**
 * 에러케이스 해결 시도(타임라인 한 칸). 본문은 자유 마크다운, 메타는 구조화(필터·요약용).
 * `insight` 는 한두 문장 요약 — UI 카드에 항상 표시.
 */
class Step private constructor(
    val id: Long?,
    val errorCaseId: Long,
    val authorUserId: Long,
    var orderIndex: Int,
    var title: String,
    var status: StepStatus,
    var attemptType: AttemptType?,
    var body: String?,
    var insight: String?,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime
) {
    init {
        require(title.isNotBlank()) { "Step title must not be blank" }
        require(title.length <= TITLE_MAX) { "Step title must be $TITLE_MAX chars or less" }
        insight?.let { require(it.length <= INSIGHT_MAX) { "Step insight must be $INSIGHT_MAX chars or less" } }
    }

    /** 부분 수정. null 인 필드는 변경 안 함. attemptType 은 clearAttemptType=true 로 명시적으로 비울 수 있다. */
    fun update(
        title: String? = null,
        status: StepStatus? = null,
        attemptType: AttemptType? = null,
        clearAttemptType: Boolean = false,
        body: String? = null,
        insight: String? = null
    ) {
        title?.let {
            require(it.isNotBlank()) { "Step title must not be blank" }
            require(it.length <= TITLE_MAX) { "Step title must be $TITLE_MAX chars or less" }
            this.title = it
        }
        status?.let { this.status = it }
        if (clearAttemptType) this.attemptType = null else attemptType?.let { this.attemptType = it }
        body?.let { this.body = it }
        insight?.let {
            require(it.length <= INSIGHT_MAX) { "Step insight must be $INSIGHT_MAX chars or less" }
            this.insight = it
        }
        this.updatedAt = LocalDateTime.now()
    }

    companion object {
        const val TITLE_MAX = 200
        const val INSIGHT_MAX = 500

        fun create(
            errorCaseId: Long,
            authorUserId: Long,
            orderIndex: Int,
            title: String,
            status: StepStatus,
            attemptType: AttemptType?,
            body: String?,
            insight: String?,
        ): Step {
            val now = LocalDateTime.now()
            return Step(
                id = null,
                errorCaseId = errorCaseId,
                authorUserId = authorUserId,
                orderIndex = orderIndex,
                title = title,
                status = status,
                attemptType = attemptType,
                body = body,
                insight = insight,
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            id: Long,
            errorCaseId: Long,
            authorUserId: Long,
            orderIndex: Int,
            title: String,
            status: StepStatus,
            attemptType: AttemptType?,
            body: String?,
            insight: String?,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): Step = Step(id, errorCaseId, authorUserId, orderIndex, title, status, attemptType, body, insight, createdAt, updatedAt)
    }
}
