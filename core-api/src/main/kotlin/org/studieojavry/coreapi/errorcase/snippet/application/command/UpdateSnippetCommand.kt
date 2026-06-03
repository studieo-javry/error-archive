package org.studieojavry.coreapi.errorcase.snippet.application.command

/**
 * 스니펫 내용 부분 수정. null 필드는 "변경 안 함"(기존 값 유지).
 * markerId / uploadedBy / uploadedAt / errorCaseId(연결)는 바뀌지 않는다 — 본문 `@snippet(markerId)` 참조 보존.
 */
data class UpdateSnippetCommand(
    val markerId: String,
    val requesterUserId: Long,
    val title: String?,
    val language: String?,
    val filePathOrClass: String?,
    val lineRange: String?,
    val caption: String?,
    val code: String?
)
