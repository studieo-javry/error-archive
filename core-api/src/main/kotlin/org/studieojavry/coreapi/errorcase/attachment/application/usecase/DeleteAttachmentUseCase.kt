package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort

/**
 * 사용자가 폼에서 첨부를 제거할 때 호출하는 즉시 삭제(B). orphan 총량을 낮게 유지해 GC 부담을 줄인다.
 *
 * 정책:
 *  - **업로더 본인만** 삭제 가능.
 *  - **미연결(errorCaseId == null) 첨부만** 삭제 가능. 이미 케이스에 묶인 첨부는 케이스 삭제 경로로
 *    처리해야 하며, 이 엔드포인트로는 거부한다(케이스 본문이 깨진 참조를 갖는 것 방지).
 *  - 실제 삭제는 [AttachmentDeleter] 단일 루틴 재사용(파일+row, 멱등).
 */
@Service
class DeleteAttachmentUseCase(
    private val repository: ErrorCaseAttachmentRepositoryPort,
    private val deleter: AttachmentDeleter,
) {
    fun invoke(markerId: String, requesterUserId: Long) {
        val attachment = repository.findByMarkerId(markerId)
            ?: throw AttachmentNotFoundException(markerId)

        if (attachment.uploadedByUserId != requesterUserId) {
            throw AttachmentDeleteForbiddenException(
                "uploader != requester (markerId=$markerId)"
            )
        }
        if (attachment.errorCaseId != null) {
            throw AttachmentDeleteForbiddenException(
                "attachment linked to case ${attachment.errorCaseId}; delete via the case (markerId=$markerId)"
            )
        }

        deleter.delete(attachment)
    }
}

class AttachmentDeleteForbiddenException(message: String) : RuntimeException(message)
