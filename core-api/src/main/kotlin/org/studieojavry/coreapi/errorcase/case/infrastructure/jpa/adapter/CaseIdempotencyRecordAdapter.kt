package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.application.port.CaseIdempotencyRecordPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.IdempotencyConflictException
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseIdempotencyJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseIdempotencyEntity
import java.time.Instant

@Repository
class CaseIdempotencyRecordAdapter(
    private val jpa: CaseIdempotencyJpaRepository,
) : CaseIdempotencyRecordPort {

    override fun find(idempotencyKey: String, userId: Long): CaseIdempotencyRecordPort.Existing? =
        jpa.findByIdempotencyKeyAndUserId(idempotencyKey, userId)
            ?.let { CaseIdempotencyRecordPort.Existing(requestHash = it.requestHash, errorCaseId = it.errorCaseId) }

    override fun save(idempotencyKey: String, userId: Long, requestHash: String, errorCaseId: Long) {
        val entity = CaseIdempotencyEntity(
            idempotencyKey = idempotencyKey,
            userId = userId,
            requestHash = requestHash,
            errorCaseId = errorCaseId,
            createdAt = Instant.now(),
        )
        try {
            // saveAndFlush 로 unique(key,user) 위반을 이 호출에서 즉시 표면화.
            jpa.saveAndFlush(entity)
        } catch (e: DataIntegrityViolationException) {
            // 동시 중복 요청이 먼저 커밋 — 현재 tx 는 롤백되고, 클라이언트가 재시도하면 replay 된다.
            throw IdempotencyConflictException("duplicate case-create request in progress — retry")
        }
    }
}
