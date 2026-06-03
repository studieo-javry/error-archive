package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.snippet.application.command.UpdateSnippetCommand
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet

/**
 * 스니펫 **내용** 수정 — 업로더 본인만. null 필드는 유지.
 * markerId 를 유지한 채 code/title 등만 갱신하므로 본문 `@snippet(markerId)` 인라인 참조가 깨지지 않는다.
 * 케이스에 연결된(errorCaseId != null) 스니펫도 수정 가능 — 연결(소속)과 내용은 별개 책임이다.
 * (어댑터 save 가 markerId 기준 upsert: uploadedBy/uploadedAt/errorCaseId 는 보존하고 내용 6필드만 갱신)
 */
@Service
class UpdateSnippetUseCase(
    private val repository: CodeSnippetRepositoryPort
) {
    @Transactional
    fun invoke(command: UpdateSnippetCommand): CodeSnippet {
        val existing = repository.findByMarkerId(command.markerId)
            ?: throw SnippetNotFoundException(command.markerId)

        if (existing.uploadedByUserId != command.requesterUserId) {
            throw SnippetAccessDeniedException("only the uploader can edit snippet ${command.markerId}")
        }

        val updated = CodeSnippet(
            markerId = existing.markerId,
            title = command.title?.takeIf { it.isNotBlank() } ?: existing.title,
            language = command.language ?: existing.language,
            filePathOrClass = command.filePathOrClass ?: existing.filePathOrClass,
            lineRange = command.lineRange ?: existing.lineRange,
            caption = command.caption ?: existing.caption,
            code = command.code ?: existing.code,
            uploadedByUserId = existing.uploadedByUserId,
            uploadedAt = existing.uploadedAt,
            errorCaseId = existing.errorCaseId
        )
        return repository.save(updated)
    }
}

/** 업로더가 아닌 사용자가 스니펫을 수정하려 할 때. → 403 */
class SnippetAccessDeniedException(message: String) : RuntimeException(message)
