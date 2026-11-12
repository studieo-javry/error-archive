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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
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

    /** 상세 설명(마크다운). V23. */
    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    /**
     * 검증 근거(SolutionVerification 직렬화 JSON). V23.
     * 직렬화/역직렬화는 어댑터가 담당(@JdbcTypeCode(SqlTypes.JSON) + columnDefinition="jsonb" — 저장 패턴).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification", columnDefinition = "jsonb")
    var verification: String? = null,

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
    companion object {
        /**
         * description 은 그대로, verification(VO→JSON) 직렬화는 어댑터가 저장 직전 채운다.
         * (엔티티 companion 은 ObjectMapper 를 주입받을 수 없으므로 String 변환은 어댑터 책임.)
         */
        fun fromDomain(s: Solution) = SolutionEntity(
            id = s.id,
            errorCaseId = s.errorCaseId,
            authorUserId = s.authorUserId,
            title = s.title,
            description = s.description,
            verification = null,
            stepIds = s.stepIds.toMutableList(),
            createdAt = s.createdAt,
            updatedAt = s.updatedAt
        )
    }
}