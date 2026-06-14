package org.studieojavry.coreapi.errorcase.comment.domain.model

/**
 * 댓글에서 멘션된 사용자. 알림 라우팅용.
 *
 * - `mentionedIdentifier`: 본문에서 파싱한 원본 식별자 (예: "이성훈", "jirubo")
 * - `mentionedUserId`: 작성 시점에 iam-api 로 *식별자→userId* 해석에 성공하면 채워짐.
 *   동명이인이면 null (다중 매칭 모호 — 알림 발송 X).
 *   해당 사용자가 그 시점에 존재하지 않으면 null.
 *   null 인 mention 도 *기록은 남김* (검색/분석용) — 단지 알림이 안 갈 뿐.
 */
class CommentMention private constructor(
    val id: Long?,
    val commentId: Long,
    val mentionedIdentifier: String,
    val mentionedUserId: Long?,
) {
    companion object {
        const val IDENTIFIER_MAX = 100
        fun create(commentId: Long, identifier: String, mentionedUserId: Long? = null) =
            CommentMention(null, commentId, identifier.trim().take(IDENTIFIER_MAX), mentionedUserId)
        fun rehydrate(id: Long, commentId: Long, identifier: String, mentionedUserId: Long?) =
            CommentMention(id, commentId, identifier, mentionedUserId)
    }
}
