package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import java.time.LocalDateTime

data class CreateErrorCaseResponse(
    val id: Long,
    val title: String,
    val status: String,
    val fingerprint: String?,
    val snippetMarkerIds: List<String>,
    val attachmentMarkerIds: List<String>,
    val createdAt: LocalDateTime
)