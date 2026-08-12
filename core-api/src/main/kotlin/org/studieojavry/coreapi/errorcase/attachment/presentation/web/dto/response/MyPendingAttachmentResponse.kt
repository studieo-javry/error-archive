package org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "미연결(pending) 첨부 1건 — 작성 화면 '이번에 올린 첨부' 트레이용")
data class MyPendingAttachmentResponse(
    val markerId: String,
    @Schema(description = "본문 임베드 토큰", example = "@attach(f0c12ab9)")
    val embedToken: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val kind: String,
    val title: String?,
    val caption: String?,
    @Schema(description = "미리보기용 presigned inline URL (짧은 TTL, 업로더 전용)")
    val previewUrl: String,
    val uploadedAt: Instant,
)
