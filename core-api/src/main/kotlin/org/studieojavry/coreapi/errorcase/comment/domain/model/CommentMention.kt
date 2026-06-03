package org.studieojavry.coreapi.errorcase.comment.domain.model

/**
 * 댓글에서 멘션된 사용자. 알림 라우팅용 (Phase 3 noti-api 연결).
 * 현재는 *식별자만* 적재 — 본문 텍스트에서 @userIdentifier 를 파싱한 결과.
 * iam-api 가 사용자 식별자(displayName 또는 username) 매핑은 추후 결합.
 */
class CommentMention private constructor(
    val id: Long?,
    val commentId: Long,
    /** @앞에 붙은 식별자 그대로(예: "이성훈"). userId 매핑은 mention 발송 직전에 별도. */
    val mentionedIdentifier: String,
) {
    companion object {
        const val IDENTIFIER_MAX = 100
        fun create(commentId: Long, identifier: String) =
            CommentMention(null, commentId, identifier.trim().take(IDENTIFIER_MAX))
        fun rehydrate(id: Long, commentId: Long, identifier: String) =
            CommentMention(id, commentId, identifier)
    }
}