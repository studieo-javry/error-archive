package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.snippet.application.command.CreateSnippetCommand
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import java.time.Instant

/**
 * 코드 스니펫 독립 생성(첨부 업로드와 대칭). markerId 를 발급해 미연결(errorCaseId=null) 상태로 저장.
 * 본문에서 `@snippet(markerId)` 로 참조하거나, 케이스 생성 시 snippetMarkerIds 로 연결한다.
 */
@Service
class CreateSnippetUseCase(
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val repository: CodeSnippetRepositoryPort
) {
    @Transactional
    fun invoke(command: CreateSnippetCommand): Result {
        val markerId = markerIdGenerator.generateSnippetMarkerId()
        val snippet = CodeSnippet(
            markerId = markerId,
            title = command.title?.takeIf { it.isNotBlank() } ?: "untitled",
            language = command.language,
            filePathOrClass = command.filePathOrClass,
            lineRange = command.lineRange,
            caption = command.caption,
            code = command.code,
            uploadedByUserId = command.uploaderUserId,
            uploadedAt = Instant.now()
        )
        val saved = repository.save(snippet)
        return Result(
            markerId = saved.markerId,
            embedToken = saved.embedToken(),
            title = saved.title,
            language = saved.language,
            caption = saved.caption
        )
    }

    data class Result(
        val markerId: String,
        val embedToken: String,
        val title: String?,
        val language: String,
        val caption: String?
    )
}
