package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.command.CreateAttachmentCommand
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import java.time.Instant

@Service
class CreateAttachmentUseCase(
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val attachmentStoragePort: AttachmentStoragePort,
    private val errorCaseAttachmentRepositoryPort: ErrorCaseAttachmentRepositoryPort
) {

    @Transactional
    fun invoke(command: CreateAttachmentCommand): Result {

        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val kind = resolveKind(command.contentType)

        val stored = attachmentStoragePort.store(
            markerId = markerId,
            fileName = command.fileName,
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
            storageUrl = stored.storageUrl,
            previewText = buildPreviewText(kind, command.bytes),
            uploadedByUserId = command.uploaderUserId,
            uploadedAt = Instant.now()
        )

        val saved = errorCaseAttachmentRepositoryPort.save(attachment)

        return Result(
            markerId = saved.markerId,
            embedToken = saved.embedToken(),
            fileName = saved.fileName,
            contentType = saved.contentType,
            size = saved.size,
            kind = saved.kind.name,
            storageUrl = saved.storageUrl
        )
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

    private fun buildPreviewText(
        kind: AttachmentKind,
        bytes: ByteArray
    ): String? {
        return when (kind) {
            AttachmentKind.JSON, AttachmentKind.TEXT -> {
                bytes.toString(Charsets.UTF_8).take(2000)
            }
            else -> null
        }
    }

    data class Result(
        val markerId: String,
        val embedToken: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        val storageUrl: String
    )
}
