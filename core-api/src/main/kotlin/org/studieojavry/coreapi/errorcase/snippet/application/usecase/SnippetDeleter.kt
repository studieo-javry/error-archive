package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentDeleter
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet


/**
 * 스니펫 1건을 삭제하는 단일 루틴. orphan GC / 명시적 삭제 / 케이스 cascade(DeleteErrorCaseUseCase)가 공유.
 * 스니펫은 파일이 없어 DB row 만 지우면 된다(첨부의 AttachmentDeleter 보다 단순). `deleteByMarkerId` 는 멱등.
 */
@Service
class SnippetDeleter(
    private val repository: CodeSnippetRepositoryPort
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun delete(snippet: CodeSnippet) {
        repository.deleteByMarkerId(snippet.markerId)
        log.debug { "[snippet] deleted markerId=${snippet.markerId} (errorCaseId=${snippet.errorCaseId})" }
    }
}
