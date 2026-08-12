package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseView
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseViewJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseViewEntity
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseViewId
import java.time.LocalDateTime

@Component
class CaseViewRepositoryAdapter(
    private val jpa: CaseViewJpaRepository,
) : CaseViewRepositoryPort {

    override fun upsert(view: CaseView) {
        val existing = jpa.findById(CaseViewId(view.userId, view.errorCaseId)).orElse(null)
        if (existing != null) {
            existing.lastViewedAt = view.lastViewedAt
            jpa.save(existing)
        } else {
            jpa.save(CaseViewEntity.fromDomain(view))
        }
    }

    override fun findByUserAndCaseIds(userId: Long, caseIds: Collection<Long>): Map<Long, LocalDateTime> {
        if (caseIds.isEmpty()) return emptyMap()
        return jpa.findAllByUserIdAndErrorCaseIdIn(userId, caseIds)
            .associate { it.errorCaseId to it.lastViewedAt }
    }

    override fun deleteAllByCaseId(errorCaseId: Long): Int = jpa.deleteAllByErrorCaseId(errorCaseId)
}
