package org.studieojavry.coreapi.errorcase.comment.domain.model

import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionSourceType
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionStatus
import java.time.LocalDateTime

/**
 * 댓글에 첨부된 Diff 제안. Comment 1:1 (한 댓글에 최대 1개 제안).
 * GitHub 의 "Suggest changes" 차용 — 코드블럭의 라인 범위에 대한 "이렇게 바꿔보면" 제안.
 *
 * 모델 단순성: oldCode / newCode 만 보관. 화면용 unified diff lines 는 응답 시점에 만든다.
 * sourceId 는 opaque String (SNIPPET=markerId / MARKDOWN_CODE=step 내부 코드블럭 식별자).
 */
class CommentSuggestion private constructor(
    val id: Long?,
    val commentId: Long,
    val sourceType: SuggestionSourceType,
    val sourceId: String,
    val startLine: Int,
    val endLine: Int,
    val oldCode: String,
    val newCode: String,
    var status: SuggestionStatus,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(startLine >= 1) { "startLine must be >= 1" }
        require(endLine >= startLine) { "endLine must be >= startLine" }
        require(oldCode.length <= CODE_MAX) { "oldCode must be $CODE_MAX chars or less" }
        require(newCode.length <= CODE_MAX) { "newCode must be $CODE_MAX chars or less" }
    }

    /** 케이스 owner 가 호출. PENDING → APPLIED / REJECTED 또는 되돌리기. */
    fun changeStatus(newStatus: SuggestionStatus, at: LocalDateTime = LocalDateTime.now()) {
        if (status == newStatus) return
        status = newStatus
        updatedAt = at
    }

    /** additions/deletions 카운트(단순 line 단위). */
    val additions: Int get() = if (newCode.isEmpty()) 0 else newCode.split('\n').size
    val deletions: Int get() = if (oldCode.isEmpty()) 0 else oldCode.split('\n').size

    companion object {
        const val CODE_MAX = 10_000

        fun create(
            commentId: Long,
            sourceType: SuggestionSourceType,
            sourceId: String,
            startLine: Int,
            endLine: Int,
            oldCode: String,
            newCode: String,
        ): CommentSuggestion {
            val now = LocalDateTime.now()
            return CommentSuggestion(
                id = null,
                commentId = commentId,
                sourceType = sourceType,
                sourceId = sourceId,
                startLine = startLine,
                endLine = endLine,
                oldCode = oldCode,
                newCode = newCode,
                status = SuggestionStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun rehydrate(
            id: Long, commentId: Long,
            sourceType: SuggestionSourceType, sourceId: String,
            startLine: Int, endLine: Int,
            oldCode: String, newCode: String,
            status: SuggestionStatus,
            createdAt: LocalDateTime, updatedAt: LocalDateTime,
        ): CommentSuggestion = CommentSuggestion(
            id, commentId, sourceType, sourceId,
            startLine, endLine, oldCode, newCode,
            status, createdAt, updatedAt,
        )
    }
}
