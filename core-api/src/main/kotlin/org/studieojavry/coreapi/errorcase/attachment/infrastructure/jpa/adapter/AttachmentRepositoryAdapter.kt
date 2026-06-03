package org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.AttachmentJpaRepository
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.entity.AttachmentEntity
import java.time.Instant

@Component
class AttachmentRepositoryAdapter(
    private val jpa: AttachmentJpaRepository
) : ErrorCaseAttachmentRepositoryPort {

    override fun save(attachment: Attachment): Attachment {
        val entity = jpa.findByMarkerId(attachment.markerId)?.also {
            it.title = attachment.title
            it.caption = attachment.caption
            it.fileName = attachment.fileName
            it.contentType = attachment.contentType
            it.size = attachment.size
            it.kind = attachment.kind
            it.storageUrl = attachment.storageUrl
            it.previewText = attachment.previewText
            // uploadedByUserId / uploadedAt 은 업로드 시점에 고정 — 갱신하지 않음
        } ?: AttachmentEntity(
            markerId = attachment.markerId,
            title = attachment.title,
            caption = attachment.caption,
            fileName = attachment.fileName,
            contentType = attachment.contentType,
            size = attachment.size,
            kind = attachment.kind,
            storageUrl = attachment.storageUrl,
            previewText = attachment.previewText,
            uploadedByUserId = attachment.uploadedByUserId,
            uploadedAt = attachment.uploadedAt
        )
        val saved = jpa.save(entity)
        return saved.toDomain()
    }

    override fun existsByMarkerId(markerId: String): Boolean = jpa.existsByMarkerId(markerId)

    override fun findByMarkerId(markerId: String): Attachment? =
        jpa.findByMarkerId(markerId)?.toDomain()

    override fun findAllByMarkerIds(markerIds: List<String>): List<Attachment> =
        if (markerIds.isEmpty()) emptyList()
        else jpa.findAllByMarkerIdIn(markerIds).map { it.toDomain() }

    override fun findAllByErrorCaseId(errorCaseId: Long): List<Attachment> =
        jpa.findAllByErrorCaseId(errorCaseId).map { it.toDomain() }

    override fun linkToCase(markerIds: List<String>, errorCaseId: Long) {
        if (markerIds.isNotEmpty()) jpa.linkToCase(markerIds, errorCaseId)
    }

    override fun unlinkFromCase(markerIds: List<String>) {
        if (markerIds.isNotEmpty()) jpa.unlinkFromCase(markerIds)
    }

    override fun deleteByMarkerId(markerId: String) = jpa.deleteByMarkerId(markerId)

    override fun findUnlinkedOlderThan(threshold: Instant, limit: Int): List<Attachment> =
        jpa.findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(threshold, PageRequest.of(0, limit))
            .map { it.toDomain() }

    private fun AttachmentEntity.toDomain(): Attachment = Attachment(
        markerId = markerId,
        title = title,
        caption = caption,
        fileName = fileName,
        contentType = contentType,
        size = size,
        kind = kind,
        storageUrl = storageUrl,
        previewText = previewText,
        uploadedByUserId = uploadedByUserId,
        uploadedAt = uploadedAt,
        errorCaseId = errorCaseId
    )
}
