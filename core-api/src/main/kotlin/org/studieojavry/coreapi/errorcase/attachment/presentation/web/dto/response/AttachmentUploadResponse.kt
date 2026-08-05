package org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response

data class AttachmentUploadResponse(
    val markerId: String,
    val embedToken: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val kind: String,
    /** 업로드 직후 미리보기용 presigned inline URL (짧은 TTL, 업로더 전용). */
    val previewUrl: String
)
