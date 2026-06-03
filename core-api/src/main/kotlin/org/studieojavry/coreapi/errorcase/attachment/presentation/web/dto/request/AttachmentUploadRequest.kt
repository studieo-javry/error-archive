package org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.request

import jakarta.validation.constraints.NotBlank

data class AttachmentUploadRequest(
    val title: String?,
    val caption: String?,

    @field:NotBlank
    val fileName: String, // 사용자가 업로드한 파일 원본 이름
    @field:NotBlank
    val contentType: String, // 파일의 포맷 정보 ex) image/png, appliation/json
    @field:NotBlank
    val size: Long, // 사용자가 업로드한 파일 크기

    val kind: String, // UI/도메인 처리 목적의 카테고리 ex) image / json / text / document ···
    val previewData: String?,
    val previewText: String?
)
