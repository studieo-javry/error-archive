package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator

/**
 * "나도 겪었어요" 토글 use case 들.
 *
 * 정책:
 *  - 본인 케이스에는 누를 수 없음 → 403
 *  - POST 멱등 — 이미 있어도 200, 같은 결과 반환
 *  - DELETE 멱등 — 없어도 200, taggedByMe=false 결과 반환
 */

data class CaseMeTooResult(
    val errorCaseId: Long,
    val count: Long,
    val taggedByMe: Boolean,
    val userIds: List<Long>,
)

@Service
class MarkCaseMeTooUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val meTooRepository: CaseMeTooRepositoryPort,
    private val cacheInvalidator: DashboardCacheInvalidator,
) {
    @Transactional
    fun invoke(errorCaseId: Long, requesterUserId: Long): CaseMeTooResult {
        val ownerUserId = errorCaseRepository.findOwnerUserIdById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        if (ownerUserId == requesterUserId) {
            throw CaseMeTooSelfNotAllowedException(errorCaseId)
        }
        meTooRepository.add(errorCaseId, requesterUserId)
        // 캐시 무효화 — case owner 의 recent-active (me-too delta 반영)
        runCatching { cacheInvalidator.evictRecentActive(ownerUserId) }
        return readResult(errorCaseId, requesterUserId)
    }

    private fun readResult(errorCaseId: Long, viewerUserId: Long): CaseMeTooResult {
        val rows = meTooRepository.listByCaseId(errorCaseId)
        val userIds = rows.map { it.userId }
        return CaseMeTooResult(
            errorCaseId = errorCaseId,
            count = rows.size.toLong(),
            taggedByMe = viewerUserId in userIds,
            userIds = userIds,
        )
    }
}

@Service
class UnmarkCaseMeTooUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val meTooRepository: CaseMeTooRepositoryPort,
    private val cacheInvalidator: DashboardCacheInvalidator,
) {
    @Transactional
    fun invoke(errorCaseId: Long, requesterUserId: Long): CaseMeTooResult {
        val ownerUserId = errorCaseRepository.findOwnerUserIdById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        meTooRepository.remove(errorCaseId, requesterUserId)
        runCatching { cacheInvalidator.evictRecentActive(ownerUserId) }
        val rows = meTooRepository.listByCaseId(errorCaseId)
        val userIds = rows.map { it.userId }
        return CaseMeTooResult(
            errorCaseId = errorCaseId,
            count = rows.size.toLong(),
            taggedByMe = false,
            userIds = userIds,
        )
    }
}

/** 케이스 상세에서 노출하기 위한 read use case. 본인 여부도 함께. */
@Service
class GetCaseMeTooUseCase(
    private val meTooRepository: CaseMeTooRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(errorCaseId: Long, viewerUserId: Long): CaseMeTooResult {
        val rows = meTooRepository.listByCaseId(errorCaseId)
        val userIds = rows.map { it.userId }
        return CaseMeTooResult(
            errorCaseId = errorCaseId,
            count = rows.size.toLong(),
            taggedByMe = viewerUserId in userIds,
            userIds = userIds,
        )
    }
}

class CaseMeTooSelfNotAllowedException(val errorCaseId: Long) :
    RuntimeException("cannot mark me-too on own case (caseId=$errorCaseId)")
