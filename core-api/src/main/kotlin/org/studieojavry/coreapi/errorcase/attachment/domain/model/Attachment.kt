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
    /** 스토리지 오브젝트 키(예: `attachments/f0/f0c12ab9`). 절대 URL 은 조회 시 presign 으로 생성. */
    val objectKey: String,
    val uploadedByUserId: Long,
    val uploadedAt: Instant,
    val errorCaseId: Long? = null
) {
    init {
        require(markerId.isNotBlank()) { "markerId must not be blank" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(contentType.isNotBlank()) { "contentType must not be blank" }
        require(size >= 0) { "size must be greater than or equal to zero" }
        require(objectKey.isNotBlank()) { "objectKey must not be blank" }
        require(uploadedByUserId > 0) { "uploadedByUserId must be positive" }
    }

    fun embedToken(): String = "@attach($markerId)"
}
