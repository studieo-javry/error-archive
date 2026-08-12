package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseView
import java.io.Serializable
import java.time.LocalDateTime

@Entity
@Table(
    name = "case_view",
    indexes = [
        Index(name = "ix_case_view_case", columnList = "error_case_id"),
    ]
)
@IdClass(CaseViewId::class)
class CaseViewEntity(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @Id
    @Column(name = "error_case_id")
    var errorCaseId: Long,

    @Column(name = "last_viewed_at", nullable = false)
    var lastViewedAt: LocalDateTime,
) {
    fun toDomain() = CaseView(userId = userId, errorCaseId = errorCaseId, lastViewedAt = lastViewedAt)

    companion object {
        fun fromDomain(v: CaseView) = CaseViewEntity(
            userId = v.userId,
            errorCaseId = v.errorCaseId,
            lastViewedAt = v.lastViewedAt,
        )
    }
}

data class CaseViewId(
    var userId: Long = 0,
    var errorCaseId: Long = 0,
) : Serializable
