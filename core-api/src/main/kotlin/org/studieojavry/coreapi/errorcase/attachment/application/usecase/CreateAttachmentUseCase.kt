package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.command.CreateAttachmentCommand
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.config.PresignTtlProperties
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import java.time.Instant

@Service
class CreateAttachmentUseCase(
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val attachmentStoragePort: AttachmentStoragePort,
    private val errorCaseAttachmentRepositoryPort: ErrorCaseAttachmentRepositoryPort,
    private val presigner: AttachmentPresigner,
    private val presignTtl: PresignTtlProperties,
) {

    @Transactional
    fun invoke(command: CreateAttachmentCommand): Result {

        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = objectKeyFor(markerId)
        val kind = resolveKind(command.contentType)

        attachmentStoragePort.put(
            objectKey = objectKey,
            bytes = command.bytes,
            contentType = command.contentType,
        )

        val attachment = Attachment(
            markerId = markerId,
            title = command.title,
            caption = command.caption,
            fileName = command.fileName,
            contentType = command.contentType,
            size = command.size,
            kind = kind,
            objectKey = objectKey,
            uploadedByUserId = command.uploaderUserId,
            uploadedAt = Instant.now()
        )

        val saved = errorCaseAttachmentRepositoryPort.save(attachment)

        // 업로드 직후(케이스 링크 前) 위저드 미리보기용 presigned URL — 업로더에게만 노출. 짧은 TTL.
        val previewUrl = presigner.viewUrl(saved.objectKey, presignTtl.restrictedTtl)

        return Result(
            markerId = saved.markerId,
            embedToken = saved.embedToken(),
            fileName = saved.fileName,
            contentType = saved.contentType,
            size = saved.size,
            kind = saved.kind.name,
            previewUrl = previewUrl,
        )
    }

    /** 스토리지 오브젝트 키. markerId 앞 2자로 샤딩. */
    private fun objectKeyFor(markerId: String): String {
        val shard = markerId.take(2).ifBlank { "00" }
        return "attachments/$shard/$markerId"
    }

    private fun resolveKind(contentType: String): AttachmentKind {
        return when {
            contentType.startsWith("image") -> AttachmentKind.IMAGE
            contentType == "application/json" -> AttachmentKind.JSON
            contentType.startsWith("text/") -> AttachmentKind.TEXT
            contentType == "application/pdf" -> AttachmentKind.DOCUMENT
            else -> AttachmentKind.OTHER
        }
    }

    data class Result(
        val markerId: String,
        val embedToken: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        /** 업로드 직후 미리보기용 presigned inline URL (짧은 TTL). */
        val previewUrl: String,
    )
}
