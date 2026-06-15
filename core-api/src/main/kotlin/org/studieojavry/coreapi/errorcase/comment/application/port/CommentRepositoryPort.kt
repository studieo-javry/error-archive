package org.studieojavry.coreapi.errorcase.comment.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentHelpful
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentMention
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentReaction
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import java.time.LocalDateTime

/**
 * Comment 애그리거트의 단일 포트. 리액션/도움됨/멘션/Diff 제안의 작은 N:1·N:M 도 같이 묶는다 —
 * 다른 sub-module(`ErrorCaseRepositoryPort` 가 snapshot/snippets 까지 다루는 패턴)과 일관.
 */
@Repository
interface CommentRepositoryPort {

    // ── Comment ────────────────────────────────────────────────
    fun save(comment: Comment): Comment
    fun findById(id: Long): Comment?

    /** top-level 댓글 + 그 답글들을 한꺼번에. 정렬은 호출자(usecase)에서. */
    fun findAllByErrorCaseId(errorCaseId: Long): List<Comment>

    fun deleteAllByErrorCaseId(errorCaseId: Long)

    /** watchlist-feed 활동 fetch — caseIds 별 globalSince 이후 (deleted 제외) 댓글, literal source=`COMMENT_POSTED`. */
    fun findActivitiesByCaseIdsSince(
        caseIds: Collection<Long>,
        globalSince: LocalDateTime,
    ): List<org.studieojavry.coreapi.errorcase.case.application.port.CaseActivityRow>

    // ── Reactions ──────────────────────────────────────────────
    fun findReactionsByCommentIds(commentIds: List<Long>): List<CommentReaction>
    fun saveReaction(reaction: CommentReaction): CommentReaction
    /** UNIQUE 위반(이미 같은 사용자가 같은 이모지 있음) 시 false. */
    fun deleteReaction(commentId: Long, userId: Long, emoji: String): Boolean
    fun deleteAllReactionsByCommentId(commentId: Long)

    // ── Helpful ────────────────────────────────────────────────
    fun findHelpfulByCommentIds(commentIds: List<Long>): List<CommentHelpful>
    fun saveHelpful(helpful: CommentHelpful): CommentHelpful
    fun deleteHelpful(commentId: Long, userId: Long): Boolean
    fun deleteAllHelpfulByCommentId(commentId: Long)

    // ── Mentions ───────────────────────────────────────────────
    fun saveMentions(mentions: List<CommentMention>)
    fun deleteAllMentionsByCommentId(commentId: Long)
    fun findMentionsByCommentIds(commentIds: List<Long>): List<CommentMention>

    // ── Suggestions (Diff 제안) ────────────────────────────────
    fun saveSuggestion(suggestion: CommentSuggestion): CommentSuggestion
    fun findSuggestionByCommentId(commentId: Long): CommentSuggestion?
    fun findSuggestionsByCommentIds(commentIds: List<Long>): List<CommentSuggestion>
    fun deleteSuggestionByCommentId(commentId: Long)
}
