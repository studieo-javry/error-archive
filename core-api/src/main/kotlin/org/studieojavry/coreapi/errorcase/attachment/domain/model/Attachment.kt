package org.studieojavry.coreapi.errorcase.attachment.domain.model

import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import java.time.Instant

class Attachment(
    val markerId: String,
    val title: String?,
    val caption: String?,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val kind: AttachmentKind,
    val storageUrl: String,
    val previewText: String?,
    val uploadedByUserId: Long,
    val uploadedAt: Instant,
    val errorCaseId: Long? = null
) {
    init {
        require(markerId.isNotBlank()) { "markerId must not be blank" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(contentType.isNotBlank()) { "contentType must not be blank" }
        require(size >= 0) { "size must be greater than or equal to zero" }
        require(storageUrl.isNotBlank()) { "storageUrl must not be blank" }
        require(uploadedByUserId > 0) { "uploadedByUserId must be positive" }
    }

    fun embedToken(): String = "@attach($markerId)"
}
