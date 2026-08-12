package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseMeToo
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_me_too",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_case_me_too_user",
            columnNames = ["error_case_id", "user_id"]
        )
    ],
    indexes = [
        Index(name = "ix_case_me_too_case", columnList = "error_case_id"),
    ]
)
class CaseMeTooEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime,
) {
    fun toDomain(): CaseMeToo = CaseMeToo.rehydrate(id!!, errorCaseId, userId, createdAt)
    companion object {
        fun fromDomain(m: CaseMeToo) = CaseMeTooEntity(
            id = m.id, errorCaseId = m.errorCaseId, userId = m.userId, createdAt = m.createdAt,
        )
    }
}
