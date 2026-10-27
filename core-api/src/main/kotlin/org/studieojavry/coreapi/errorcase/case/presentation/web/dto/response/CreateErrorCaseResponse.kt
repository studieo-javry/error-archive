package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import java.time.LocalDateTime

data class CreateErrorCaseResponse(
    val id: Long,
    val title: String,
    val status: String,
    val fingerprint: String?,
    val snippetMarkerIds: List<String>,
    val attachmentMarkerIds: List<String>,
    val createdAt: LocalDateTime,
    /** 저장된 심각도("S1".."S4") — 미지정이면 null. */
    val severity: String? = null,
)