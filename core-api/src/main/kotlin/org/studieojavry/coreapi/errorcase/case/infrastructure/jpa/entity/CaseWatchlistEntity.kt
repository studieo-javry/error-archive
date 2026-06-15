package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseWatchlist
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case_watchlist",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_case_watchlist_user",
            columnNames = ["error_case_id", "user_id"]
        )
    ],
    indexes = [
        Index(name = "ix_case_watchlist_case", columnList = "error_case_id"),
        Index(name = "ix_case_watchlist_user", columnList = "user_id"),
    ]
)
class CaseWatchlistEntity(
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
    fun toDomain(): CaseWatchlist = CaseWatchlist.rehydrate(id!!, errorCaseId, userId, createdAt)
    companion object {
        fun fromDomain(w: CaseWatchlist) = CaseWatchlistEntity(
            id = w.id, errorCaseId = w.errorCaseId, userId = w.userId, createdAt = w.createdAt,
        )
    }
}
