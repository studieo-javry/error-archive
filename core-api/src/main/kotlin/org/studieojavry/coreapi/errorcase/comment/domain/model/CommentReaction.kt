package org.studieojavry.coreapi.errorcase.comment.domain.model

import java.time.LocalDateTime

/**
 * 댓글 이모지 리액션. Slack 스타일: 한 사용자가 한 댓글에 같은 이모지 1회만.
 * UNIQUE (commentId, userId, emoji) — DB 제약으로 강제.
 */
class CommentReaction private constructor(
    val id: Long?,
    val commentId: Long,
    val userId: Long,
    val emoji: String,
    val createdAt: LocalDateTime,
) {
    init {
        require(emoji.isNotBlank()) { "emoji must not be blank" }
        require(emoji.length <= EMOJI_MAX) { "emoji must be $EMOJI_MAX chars or less" }
    }

    companion object {
        const val EMOJI_MAX = 16
        fun create(commentId: Long, userId: Long, emoji: String) =
            CommentReaction(null, commentId, userId, emoji.trim(), LocalDateTime.now())
        fun rehydrate(id: Long, commentId: Long, userId: Long, emoji: String, createdAt: LocalDateTime) =
            CommentReaction(id, commentId, userId, emoji, createdAt)
    }
}
