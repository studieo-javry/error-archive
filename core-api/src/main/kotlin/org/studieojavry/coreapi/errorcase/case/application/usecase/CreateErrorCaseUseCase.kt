package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.command.CreateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.shared.util.FingerprintGenerator

@Service
class CreateErrorCaseUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val snippetRepository: CodeSnippetRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
    private val errorSnapshotExtractor: ErrorSnaphostExtractorPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
) {

    @Transactional
    fun invoke(command: CreateErrorCaseCommand): Result {
        // visibility 일관성 검사 — workspaceId 없이 WORKSPACE 는 도메인 invariant 위반(400)
        if (command.visibility == Visibility.WORKSPACE && command.workspaceId == null) {
            throw ErrorCaseLinkException("Visibility.WORKSPACE requires workspaceId")
        }

        if (command.workspaceId != null) {
            // 멤버 여부뿐 아니라 역할까지 확인 — 워크스페이스에 작성하려면 WRITE 이상.
            val role = workspaceQuery.getViewerRole(command.userId, command.workspaceId)
                ?: throw WorkspaceAccessDeniedException("workspace not accessible: ${command.workspaceId}")
            if (!role.canWriteContent()) {
                throw WorkspaceAccessDeniedException(
                    "WRITE role required to create an error case in workspace ${command.workspaceId} (current=$role)"
                )
            }
            // 워크스페이스 케이스를 PUBLIC 으로 생성하려면 워크스페이스 ADMIN 만 (자산 외부 노출 정책).
            if (command.visibility == Visibility.PUBLIC && !role.canAdminister()) {
                throw WorkspaceAccessDeniedException(
                    "workspace ADMIN required to create a PUBLIC error case in workspace ${command.workspaceId} (current=$role)"
                )
            }
        }

        val attachments = resolveAttachments(command.attachmentMarkerIds, command.userId)
        val snippets = resolveSnippets(command.snippetMarkerIds, command.userId)
        val snapshot = command.paste?.takeIf { it.isNotBlank() }?.let { buildSnapshot(it) }
        val meta = Meta.create(
            workspaceId = command.workspaceId,
            severityCode = command.severityCode,
        )

        // 태그 정규화: trim·소문자·distinct, 빈 값 제거, max 20개 컷
        val normalizedTags = command.tags
            ?.mapNotNull { ErrorCase.normalizeTag(it) }
            ?.distinct()
            ?.take(ErrorCase.TAGS_MAX_PER_CASE)
            .orEmpty()

        val saved = errorCaseRepository.save(
            ErrorCase.create(
                ownerUserId = command.userId,
                title = command.title,
                project = command.project,
                snapshot = snapshot,
                description = command.description,
                meta = meta,
                visibility = command.visibility,
                snippets = snippets,
                attachments = attachments,
                tags = normalizedTags,
                occurredAt = command.occurredAt
            )
        )

        // 태그를 별도 테이블에 멱등 저장
        normalizedTags.forEach { tagRepository.add(saved.id!!, it) }

        return Result(
            id = requireNotNull(saved.id) { "saved error case must have id" },
            title = saved.title,
            status = saved.status.name,
            fingerprint = saved.snapshot?.fingeprint?.value,
            snippetMarkerIds = saved.snippets.map { it.markerId },
            attachmentMarkerIds = saved.attachments.map { it.markerId },
            createdAt = saved.createdAt
        )
    }

    private fun buildSnapshot(paste: String): ErrorSnapshot? {
        val extracted = errorSnapshotExtractor.extract(paste)

        val rawStackTrace = extracted.rawStackTrace
            ?.takeIf { it.isNotBlank() }
            ?.let { RawStackTrace(it) }

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

    private fun resolveAttachments(markerIds: List<String>, ownerUserId: Long): List<Attachment> {
        if (markerIds.isEmpty()) return emptyList()
        val unique = markerIds.distinct()
        val found = attachmentRepository.findAllByMarkerIds(unique)
        val foundIds = found.map { it.markerId }.toSet()
        val missing = unique.filterNot { it in foundIds }
        if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown attachment marker(s): $missing")

        // 다른 사용자가 업로드한 첨부를 자기 케이스에 묶는 것을 차단.
        val foreign = found.filter { it.uploadedByUserId != ownerUserId }
        if (foreign.isNotEmpty()) {
            throw ErrorCaseLinkException("attachment(s) not owned by requester: ${foreign.map { it.markerId }}")
        }
        // 이미 다른 케이스에 연결된 첨부를 가로채는 것을 차단(가로채면 원 케이스에서 떨어져 나감).
        val linked = found.filter { it.errorCaseId != null }
        if (linked.isNotEmpty()) {
            throw ErrorCaseLinkException("attachment(s) already linked to another case: ${linked.map { it.markerId }}")
        }

        return found
    }

    /** 첨부와 동일한 패턴: 먼저 독립 생성된 스니펫을 markerId 로 찾아 검증(존재/소유자/미연결) 후 연결 대상으로 반환. */
    private fun resolveSnippets(markerIds: List<String>, ownerUserId: Long): List<CodeSnippet> {
        if (markerIds.isEmpty()) return emptyList()
        val unique = markerIds.distinct()
        val found = snippetRepository.findAllByMarkerIds(unique)
        val foundIds = found.map { it.markerId }.toSet()
        val missing = unique.filterNot { it in foundIds }
        if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown snippet marker(s): $missing")

        val foreign = found.filter { it.uploadedByUserId != ownerUserId }
        if (foreign.isNotEmpty()) {
            throw ErrorCaseLinkException("snippet(s) not owned by requester: ${foreign.map { it.markerId }}")
        }
        val linked = found.filter { it.errorCaseId != null }
        if (linked.isNotEmpty()) {
            throw ErrorCaseLinkException("snippet(s) already linked to another case: ${linked.map { it.markerId }}")
        }

        return found
    }

    data class Result(
        val id: Long,
        val title: String,
        val status: String,
        val fingerprint: String?,
        val snippetMarkerIds: List<String>,
        val attachmentMarkerIds: List<String>,
        val createdAt: java.time.LocalDateTime
    )
}

/** 워크스페이스 멤버가 아니거나 역할이 부족(READ 가 작성 시도)할 때. → 403 */
class WorkspaceAccessDeniedException(message: String) : RuntimeException(message)
