package org.studieojavry.coreapi.errorcase.comment.application.command

import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionSourceType
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionStatus

data class CreateCommentCommand(
    val errorCaseId: Long,
    val authorUserId: Long,
    val parentCommentId: Long?,
    val body: String,
    val quoteSourceKind: QuoteSourceKind,
    val quoteSourceId: Long?,
    val quoteSnapshot: String?,
    /** GitHub 스타일 Diff 제안. null=일반 댓글, non-null=제안 댓글(quote 와 함께 옴 — oldCode == quoteSnapshot 권장). */
    val suggestion: SuggestionInput? = null,
) {
    data class SuggestionInput(
        val sourceType: SuggestionSourceType,
        val sourceId: String,
        val startLine: Int,
        val endLine: Int,
        val oldCode: String,
        val newCode: String,
    )
}

data class UpdateCommentCommand(
    val commentId: Long,
    val requesterUserId: Long,
    val body: String,
)

/** Diff 제안 상태 변경 — 케이스 owner 만. */
data class UpdateSuggestionStatusCommand(
    val commentId: Long,
    val requesterUserId: Long,
    val newStatus: SuggestionStatus,
)
