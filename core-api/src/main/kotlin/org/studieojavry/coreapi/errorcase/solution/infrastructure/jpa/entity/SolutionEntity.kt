package org.studieojavry.coreapi.errorcase.solution.infrastructure.jpa.entity

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_solution",
    indexes = [
        Index(name = "ix_solution_case", columnList = "error_case_id"),
        Index(name = "ix_solution_author", columnList = "author_user_id"),
    ]
)
class SolutionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "author_user_id", nullable = false)
    var authorUserId: Long,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    /**
     * 묶인 step id 목록. join table `solution_step_link(solution_id, step_id, order_index)`.
     * `@OrderColumn` 이 사용자 선택 순서를 보존한다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "error_case_solution_step",
        joinColumns = [JoinColumn(name = "solution_id")],
        indexes = [Index(name = "ix_solution_step_step", columnList = "step_id")],
    )
    @OrderColumn(name = "order_index")
    @Column(name = "step_id", nullable = false)
    var stepIds: MutableList<Long>,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    fun toDomain(): Solution = Solution.rehydrate(
        id = id!!,
        errorCaseId = errorCaseId,
        authorUserId = authorUserId,
        title = title,
        stepIds = stepIds,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(s: Solution) = SolutionEntity(
            id = s.id,
            errorCaseId = s.errorCaseId,
            authorUserId = s.authorUserId,
            title = s.title,
            stepIds = s.stepIds.toMutableList(),
            createdAt = s.createdAt,
            updatedAt = s.updatedAt
        )
    }
}