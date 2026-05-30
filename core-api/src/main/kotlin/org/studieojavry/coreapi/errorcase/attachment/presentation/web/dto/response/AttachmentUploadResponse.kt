package org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response

data class AttachmentUploadResponse(
    val markerId: String,
    val embedToken: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val kind: String,
    val storageUrl: String
)
