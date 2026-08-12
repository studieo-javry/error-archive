package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase


@Service
class DownloadAttachmentUseCase(
    private val errorCaseAttachmentRepositoryPort: ErrorCaseAttachmentRepositoryPort,
    private val errorCaseRepositoryPort: ErrorCaseRepositoryPort,
    private val attachmentStoragePort: AttachmentStoragePort
) {

    private val logger = KotlinLogging.logger {}

    @Transactional(readOnly = true)
    fun invoke(markerId: String, requesterUserId: Long): Result {

        logger.info { "Downloading attachment markerId=$markerId by user=$requesterUserId" }

        logger.info { errorCaseAttachmentRepositoryPort.findByMarkerId(markerId) }

        val attachment = errorCaseAttachmentRepositoryPort.findByMarkerId(markerId)
            ?: throw AttachmentNotFoundException(markerId)

        logger.info { "attachment is exist" }

        authorize(attachment, requesterUserId)

        val bytes = attachmentStoragePort.getBytes(attachment.objectKey)

        return Result(
            markerId = attachment.markerId,
            fileName = attachment.fileName,
            contentType = attachment.contentType,
            size = attachment.size,
            bytes = bytes
        )
    }

    /**
     * - 케이스 미연결 (`errorCaseId == null`): Preview 시나리오 — 업로드한 본인만 가능.
     * - 케이스 연결 후: ErrorCase 의 ownerUserId 만 가능.
     */
    private fun authorize(
        attachment: org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment,
        requesterUserId: Long
    ) {
        val caseId = attachment.errorCaseId
        if (caseId == null) {
            if (attachment.uploadedByUserId != requesterUserId) {
                throw AttachmentDownloadForbiddenException(
                    "pending attachment: uploader != requester (markerId=${attachment.markerId})"
                )
            }
            return
        }

        val ownerUserId = errorCaseRepositoryPort.findOwnerUserIdById(caseId)
            ?: throw AttachmentDownloadForbiddenException(
                "linked error case missing: caseId=$caseId markerId=${attachment.markerId}"
            )
        if (ownerUserId != requesterUserId) {
            throw AttachmentDownloadForbiddenException(
                "case owner != requester (markerId=${attachment.markerId}, caseId=$caseId)"
            )
        }
    }

    data class Result(
        val markerId: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val bytes: ByteArray
    )
}

// AttachmentNotFoundException 는 DeleteAttachmentUseCase.kt 에 단일 선언(같은 패키지 공유) — 여기선 중복 선언 제거.

class AttachmentDownloadForbiddenException(message: String) : RuntimeException(message)
