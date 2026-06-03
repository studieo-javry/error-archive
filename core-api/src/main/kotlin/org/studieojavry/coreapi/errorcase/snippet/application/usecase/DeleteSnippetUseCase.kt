package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.DeleteAttachmentUseCase
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort


/**
 * 사용자가 스니펫 카드를 제거할 때의 즉시 삭제(첨부 DeleteAttachmentUseCase 와 대칭).
 *  - 업로더 본인만.
 *  - 미연결(errorCaseId == null)만. 케이스에 묶인 스니펫은 케이스 삭제 경로로(본문 깨진 참조 방지).
 */
@Service
class DeleteSnippetUseCase(
    private val repository: CodeSnippetRepositoryPort,
    private val deleter: SnippetDeleter
) {
    fun invoke(markerId: String, requesterUserId: Long) {
        val snippet = repository.findByMarkerId(markerId)
            ?: throw SnippetNotFoundException(markerId)

        if (snippet.uploadedByUserId != requesterUserId) {
            throw SnippetDeleteForbiddenException("uploader != requester (markerId=$markerId)")
        }
        if (snippet.errorCaseId != null) {
            throw SnippetDeleteForbiddenException(
                "snippet linked to case ${snippet.errorCaseId}; delete via the case (markerId=$markerId)"
            )
        }

        deleter.delete(snippet)
    }
}

class SnippetNotFoundException(val markerId: String) :
    RuntimeException("snippet not found: markerId=$markerId")

class SnippetDeleteForbiddenException(message: String) : RuntimeException(message)
