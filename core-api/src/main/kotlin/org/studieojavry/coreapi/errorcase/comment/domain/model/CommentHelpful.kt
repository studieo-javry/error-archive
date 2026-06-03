package org.studieojavry.coreapi.errorcase.comment.domain.model

import java.time.LocalDateTime

/**
 * 댓글에 "도움됨"을 표시한 사용자 1건. **모든 사용자가 토글 가능**.
 * UNIQUE (commentId, userId).
 */
class CommentHelpful private constructor(
    val id: Long?,
    val commentId: Long,
    val userId: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(commentId: Long, userId: Long) =
            CommentHelpful(null, commentId, userId, LocalDateTime.now())
        fun rehydrate(id: Long, commentId: Long, userId: Long, createdAt: LocalDateTime) =
            CommentHelpful(id, commentId, userId, createdAt)
    }
}
