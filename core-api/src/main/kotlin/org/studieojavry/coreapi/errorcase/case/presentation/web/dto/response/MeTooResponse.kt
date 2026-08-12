package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

/** "나도 겪었어요" 토글 응답 — 현재 카운트 + viewer 본인의 상태 + 누른 사용자 ID 목록. */
data class MeTooResponse(
    val count: Long,
    val taggedByMe: Boolean,
    val userIds: List<Long>,
)
