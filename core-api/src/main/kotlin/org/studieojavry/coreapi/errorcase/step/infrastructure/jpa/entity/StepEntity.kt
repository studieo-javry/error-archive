package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_step",
    indexes = [
        Index(name = "ix_step_case_order", columnList = "error_case_id, order_index"),
        Index(name = "ix_step_author", columnList = "author_user_id"),
    ]
)
class StepEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "author_user_id", nullable = false)
    var authorUserId: Long,

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: StepStatus,

    /** 자유 String — system 카탈로그(`AttemptType.SYSTEM`) 또는 사용자 커스텀. */
    @Column(name = "attempt_type", length = 64)
    var attemptType: String?,

    /** 자유 마크다운, `@snippet(...)`/`@attach(...)` 토큰 임베드 가능. 비어도 됨. */
    @Column(name = "body", columnDefinition = "TEXT")
    var body: String?,

    /** 한두 문장 요약(≤500). 타임라인 카드에 항상 표시. */
    @Column(name = "insight", length = 500)
    var insight: String?,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {

    fun toDomain(): Step = Step.rehydrate(
        id = id!!,
        errorCaseId = errorCaseId,
        authorUserId = authorUserId,
        orderIndex = orderIndex,
        title = title,
        status = status,
        attemptType = attemptType,
        body = body,
        insight = insight,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(step: Step) = StepEntity(
            id = step.id,
            errorCaseId = step.errorCaseId,
            authorUserId = step.authorUserId,
            orderIndex = step.orderIndex,
            title = step.title,
            status = step.status,
            attemptType = step.attemptType,
            body = step.body,
            insight = step.insight,
            createdAt = step.createdAt,
            updatedAt = step.updatedAt
        )
    }
}
