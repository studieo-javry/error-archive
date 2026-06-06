package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.command.UpdateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.shared.util.FingerprintGenerator

/**
 * 에러케이스 부분 수정 — **소유자만**.
 * null 필드는 변경 안 함. `paste` 가 오면 스냅샷/지문을 재추출한다.
 *
 * 스니펫·첨부는 **선언형 재연결**: 요청의 marker 집합을 "최종 상태"로 보고 diff 한다.
 *  - null  → 유지(건드리지 않음)
 *  - []    → 전부 연결 해제(→ orphan → GC)
 *  - [..]  → 그 집합이 되도록 추가/해제
 * (워크스페이스 이동은 범위 밖)
 */
@Service
class UpdateErrorCaseUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val snippetRepository: CodeSnippetRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val errorSnapshotExtractor: ErrorSnaphostExtractorPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(command: UpdateErrorCaseCommand): ErrorCase {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)

        if (errorCase.ownerUserId != command.requesterUserId) {
            throw ErrorCaseAccessDeniedException("only the owner can edit error case ${command.errorCaseId}")
        }

        // 워크스페이스 케이스의 PUBLIC 승격은 워크스페이스 ADMIN 만 (자산 외부 노출 정책).
        val newVisibility = command.visibility ?: errorCase.visibility
        if (
            errorCase.meta.workspaceId != null &&
            errorCase.visibility != Visibility.PUBLIC &&
            newVisibility == Visibility.PUBLIC
        ) {
            access.requirePublicPromotion(errorCase, command.requesterUserId)
        }

        val newMeta = Meta.create(
            workspaceId = errorCase.meta.workspaceId, // 워크스페이스 이동은 불가(범위 밖)
            severityCode = command.severityCode ?: errorCase.meta.severity?.code,
        )
        val newSnapshot = if (command.paste != null) buildSnapshot(command.paste) else errorCase.snapshot

        errorCase.update(
            title = command.title ?: errorCase.title,
            project = command.project ?: errorCase.project,
            snapshot = newSnapshot,
            description = command.description ?: errorCase.description,
            meta = newMeta,
            visibility = newVisibility,
        )
        // 상태 전이는 도메인 transitionTo 가 검증. 같은 상태면 no-op.
        command.status?.let { errorCase.transitionTo(it) }
        errorCaseRepository.update(errorCase)

        // 스니펫·첨부 선언형 재연결(diff). 같은 트랜잭션.
        reconcileSnippets(command.snippetMarkerIds, command.errorCaseId, command.requesterUserId)
        reconcileAttachments(command.attachmentMarkerIds, command.errorCaseId, command.requesterUserId)

        // 재연결이 반영된 최신 애그리거트를 다시 로드해 반환(벌크 UPDATE 후 영속성 컨텍스트는 clear 됨).
        return errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
    }

    /**
     * desired(요청 marker 집합)와 current(현재 연결된 marker 집합)의 차집합으로 추가/해제를 계산한다.
     *  - toAdd = desired − current : 존재·소유·미연결 검증 후 연결
     *  - toRemove = current − desired : 연결 해제(→ orphan → GC)
     */
    private fun reconcileSnippets(desired: List<String>?, caseId: Long, ownerUserId: Long) {
        if (desired == null) return // 필드 미포함 → 유지

        val want = desired.distinct().toSet()
        val current = snippetRepository.findAllByErrorCaseId(caseId).map { it.markerId }.toSet()
        val toAdd = want - current
        val toRemove = current - want

        if (toAdd.isNotEmpty()) {
            val found = snippetRepository.findAllByMarkerIds(toAdd.toList())
            val foundIds = found.map { it.markerId }.toSet()
            val missing = toAdd - foundIds
            if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown snippet marker(s): $missing")
            found.forEach { s ->
                if (s.uploadedByUserId != ownerUserId) {
                    throw ErrorCaseLinkException("snippet ${s.markerId} is not owned by requester")
                }
                if (s.errorCaseId != null) {
                    throw ErrorCaseLinkException("snippet ${s.markerId} is already linked to another case")
                }
            }
            snippetRepository.linkToCase(toAdd.toList(), caseId)
        }
        if (toRemove.isNotEmpty()) {
            snippetRepository.unlinkFromCase(toRemove.toList())
        }
    }

    private fun reconcileAttachments(desired: List<String>?, caseId: Long, ownerUserId: Long) {
        if (desired == null) return

        val want = desired.distinct().toSet()
        val current = attachmentRepository.findAllByErrorCaseId(caseId).map { it.markerId }.toSet()
        val toAdd = want - current
        val toRemove = current - want

        if (toAdd.isNotEmpty()) {
            val found = attachmentRepository.findAllByMarkerIds(toAdd.toList())
            val foundIds = found.map { it.markerId }.toSet()
            val missing = toAdd - foundIds
            if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown attachment marker(s): $missing")
            found.forEach { a ->
                if (a.uploadedByUserId != ownerUserId) {
                    throw ErrorCaseLinkException("attachment ${a.markerId} is not owned by requester")
                }
                if (a.errorCaseId != null) {
                    throw ErrorCaseLinkException("attachment ${a.markerId} is already linked to another case")
                }
            }
            attachmentRepository.linkToCase(toAdd.toList(), caseId)
        }
        if (toRemove.isNotEmpty()) {
            attachmentRepository.unlinkFromCase(toRemove.toList())
        }
    }

    private fun buildSnapshot(paste: String): ErrorSnapshot? {
        val extracted = errorSnapshotExtractor.extract(paste)
        val rawStackTrace = extracted.rawStackTrace?.takeIf { it.isNotBlank() }?.let { RawStackTrace(it) }
        val fingerprint = FingerprintGenerator.generate(
            exceptionClass = extracted.exceptionClass,
            rawStackTrace = rawStackTrace
        )
        val snapshot = ErrorSnapshot(
            rawPaste = paste,
            exceptionClass = extracted.exceptionClass,
            exceptionMessage = extracted.exceptionMessage,
            rawStackTrace = rawStackTrace,
            fingeprint = fingerprint
        )
        return snapshot.takeUnless { it.isEmpty() }
    }
}

/** 스니펫/첨부 재연결 요청이 유효하지 않음(미존재·타인 소유·이미 다른 케이스에 연결). → 400. */
class ErrorCaseLinkException(message: String) : RuntimeException(message)
