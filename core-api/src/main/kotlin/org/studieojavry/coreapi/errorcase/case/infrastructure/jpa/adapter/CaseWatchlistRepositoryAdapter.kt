package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseWatchlist
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseWatchlistJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseWatchlistEntity

@Component
class CaseWatchlistRepositoryAdapter(
    private val jpa: CaseWatchlistJpaRepository,
) : CaseWatchlistRepositoryPort {

    override fun add(errorCaseId: Long, userId: Long): CaseWatchlist {
        jpa.findByErrorCaseIdAndUserId(errorCaseId, userId)?.let { return it.toDomain() }
        return try {
            jpa.save(CaseWatchlistEntity.fromDomain(CaseWatchlist.create(errorCaseId, userId))).toDomain()
        } catch (e: DataIntegrityViolationException) {
            jpa.findByErrorCaseIdAndUserId(errorCaseId, userId)?.toDomain() ?: throw e
        }
    }

    override fun remove(errorCaseId: Long, userId: Long): Int =
        jpa.deleteByErrorCaseIdAndUserId(errorCaseId, userId)

    override fun exists(errorCaseId: Long, userId: Long): Boolean =
        jpa.findByErrorCaseIdAndUserId(errorCaseId, userId) != null

    override fun findUserIdsByCaseId(errorCaseId: Long): List<Long> =
        jpa.findUserIdsByErrorCaseId(errorCaseId)

    override fun findCaseIdsByUserId(userId: Long, limit: Int): List<Long> =
        jpa.findCaseIdsByUserId(userId, PageRequest.of(0, limit))

    override fun findEntriesByUserId(userId: Long, limit: Int): List<CaseWatchlistRepositoryPort.Entry> =
        jpa.findEntriesByUserId(userId, PageRequest.of(0, limit))
            .map { CaseWatchlistRepositoryPort.Entry(caseId = it.caseId, addedAt = it.addedAt) }

    override fun deleteAllByCaseId(errorCaseId: Long): Int = jpa.deleteAllByErrorCaseId(errorCaseId)

    override fun findCoOccurringUserIds(userId: Long, limit: Int): Map<Long, Long> {
        return jpa.findCoOccurringUserIds(userId, org.springframework.data.domain.PageRequest.of(0, limit))
            .associate { it.userId to it.count }
    }
}
