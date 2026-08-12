package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.ErrorCaseTagJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseTagEntity

@Component
class ErrorCaseTagRepositoryAdapter(
    private val jpa: ErrorCaseTagJpaRepository,
) : ErrorCaseTagRepositoryPort {

    override fun findAllByErrorCaseId(errorCaseId: Long): List<String> =
        jpa.findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId).map { it.tag }

    override fun count(errorCaseId: Long): Long = jpa.countByErrorCaseId(errorCaseId)

    override fun add(errorCaseId: Long, tag: String): Boolean {
        if (jpa.findByErrorCaseIdAndTag(errorCaseId, tag) != null) return false
        jpa.save(ErrorCaseTagEntity(errorCaseId = errorCaseId, tag = tag))
        return true
    }

    override fun remove(errorCaseId: Long, tag: String): Int =
        jpa.deleteByErrorCaseIdAndTag(errorCaseId, tag)

    override fun deleteAllByErrorCaseId(errorCaseId: Long) {
        jpa.deleteAllByErrorCaseId(errorCaseId)
    }
}
