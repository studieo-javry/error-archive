package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseMeToo
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.CaseMeTooJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.CaseMeTooEntity

@Component
class CaseMeTooRepositoryAdapter(
    private val jpa: CaseMeTooJpaRepository,
) : CaseMeTooRepositoryPort {

    /**
     * 1) 사전 exists 체크로 *대부분의 멱등 호출* 을 cheap 하게 처리 (SELECT 1)
     * 2) INSERT 시도 — race 시 UNIQUE 위반은 catch 해 기존 row 재조회 후 반환
     */
    override fun add(errorCaseId: Long, userId: Long): CaseMeToo {
        jpa.findByErrorCaseIdAndUserId(errorCaseId, userId)?.let { return it.toDomain() }
        return try {
            jpa.save(CaseMeTooEntity.fromDomain(CaseMeToo.create(errorCaseId, userId))).toDomain()
        } catch (e: DataIntegrityViolationException) {
            // race — 다른 트랜잭션이 동시에 INSERT 함. 기존 row 가 보이게 됨.
            jpa.findByErrorCaseIdAndUserId(errorCaseId, userId)?.toDomain()
                ?: throw e
        }
    }

    override fun remove(errorCaseId: Long, userId: Long): Int =
        jpa.deleteByErrorCaseIdAndUserId(errorCaseId, userId)

    override fun listByCaseId(errorCaseId: Long): List<CaseMeToo> =
        jpa.findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId).map { it.toDomain() }

    override fun count(errorCaseId: Long): Long = jpa.countByErrorCaseId(errorCaseId)

    override fun exists(errorCaseId: Long, userId: Long): Boolean =
        jpa.findByErrorCaseIdAndUserId(errorCaseId, userId) != null

    override fun deleteAllByCaseId(errorCaseId: Long): Int = jpa.deleteAllByErrorCaseId(errorCaseId)
}
