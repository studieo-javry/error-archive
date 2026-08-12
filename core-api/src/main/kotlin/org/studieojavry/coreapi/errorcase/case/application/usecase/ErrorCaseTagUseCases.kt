package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * 케이스 태그 추가 — 케이스 WRITE 권한자. 정규화(trim·소문자) + max 20/case + 멱등.
 */
@Service
class AddErrorCaseTagUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(errorCaseId: Long, requesterUserId: Long, rawTag: String): Result {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireWrite(errorCase, requesterUserId)

        val normalized = ErrorCase.normalizeTag(rawTag)
            ?: throw ErrorCaseLinkException("tag must not be blank")

        val current = tagRepository.count(errorCaseId)
        if (current >= ErrorCase.TAGS_MAX_PER_CASE && tagRepository.findAllByErrorCaseId(errorCaseId).none { it == normalized }) {
            throw ErrorCaseLinkException("tag count exceeded: max ${ErrorCase.TAGS_MAX_PER_CASE} per case")
        }

        val added = tagRepository.add(errorCaseId, normalized)
        val all = tagRepository.findAllByErrorCaseId(errorCaseId)
        return Result(tag = normalized, added = added, allTags = all)
    }

    data class Result(val tag: String, val added: Boolean, val allTags: List<String>)
}

/** 케이스 태그 제거 — 케이스 WRITE 권한자. 멱등. */
@Service
class RemoveErrorCaseTagUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(errorCaseId: Long, requesterUserId: Long, rawTag: String): Result {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireWrite(errorCase, requesterUserId)

        val normalized = ErrorCase.normalizeTag(rawTag)
            ?: throw ErrorCaseLinkException("tag must not be blank")

        val removed = tagRepository.remove(errorCaseId, normalized)
        val all = tagRepository.findAllByErrorCaseId(errorCaseId)
        return Result(tag = normalized, removed = removed > 0, allTags = all)
    }

    data class Result(val tag: String, val removed: Boolean, val allTags: List<String>)
}
