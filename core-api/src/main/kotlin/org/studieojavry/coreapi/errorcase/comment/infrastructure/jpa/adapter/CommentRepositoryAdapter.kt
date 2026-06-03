package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentHelpful
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentMention
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentReaction
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CommentHelpfulJpaRepository
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CommentJpaRepository
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CommentMentionJpaRepository
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CommentReactionJpaRepository
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.CommentSuggestionJpaRepository
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentEntity
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentHelpfulEntity
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentMentionEntity
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentReactionEntity
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentSuggestionEntity

@Component
class CommentRepositoryAdapter(
    private val commentJpa: CommentJpaRepository,
    private val reactionJpa: CommentReactionJpaRepository,
    private val helpfulJpa: CommentHelpfulJpaRepository,
    private val mentionJpa: CommentMentionJpaRepository,
    private val suggestionJpa: CommentSuggestionJpaRepository,
) : CommentRepositoryPort {

    // ── Comment ────────────────────────────────────────────────
    override fun save(comment: Comment): Comment {
        val entity = commentJpa.findById(comment.id ?: -1).orElse(null)?.also {
            // 기존 엔티티가 있으면 변경 가능 필드만 갱신(불변 필드 보호)
            it.body = comment.body
            it.editedAt = comment.editedAt
            it.deletedAt = comment.deletedAt
            it.updatedAt = comment.updatedAt
        } ?: CommentEntity.fromDomain(comment)
        return commentJpa.save(entity).toDomain()
    }

    override fun findById(id: Long): Comment? = commentJpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByErrorCaseId(errorCaseId: Long): List<Comment> =
        commentJpa.findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId).map { it.toDomain() }

    override fun deleteAllByErrorCaseId(errorCaseId: Long) {
        commentJpa.deleteAllByErrorCaseId(errorCaseId)
    }

    // ── Reactions ──────────────────────────────────────────────
    override fun findReactionsByCommentIds(commentIds: List<Long>): List<CommentReaction> =
        if (commentIds.isEmpty()) emptyList()
        else reactionJpa.findAllByCommentIdIn(commentIds).map { it.toDomain() }

    override fun saveReaction(reaction: CommentReaction): CommentReaction =
        reactionJpa.save(CommentReactionEntity.fromDomain(reaction)).toDomain()

    override fun deleteReaction(commentId: Long, userId: Long, emoji: String): Boolean =
        reactionJpa.deleteByCommentUserEmoji(commentId, userId, emoji) > 0

    override fun deleteAllReactionsByCommentId(commentId: Long) {
        reactionJpa.deleteByCommentId(commentId)
    }

    // ── Helpful ────────────────────────────────────────────────
    override fun findHelpfulByCommentIds(commentIds: List<Long>): List<CommentHelpful> =
        if (commentIds.isEmpty()) emptyList()
        else helpfulJpa.findAllByCommentIdIn(commentIds).map { it.toDomain() }

    override fun saveHelpful(helpful: CommentHelpful): CommentHelpful =
        helpfulJpa.save(CommentHelpfulEntity.fromDomain(helpful)).toDomain()

    override fun deleteHelpful(commentId: Long, userId: Long): Boolean =
        helpfulJpa.deleteByCommentUser(commentId, userId) > 0

    override fun deleteAllHelpfulByCommentId(commentId: Long) {
        helpfulJpa.deleteByCommentId(commentId)
    }

    // ── Mentions ───────────────────────────────────────────────
    override fun saveMentions(mentions: List<CommentMention>) {
        if (mentions.isEmpty()) return
        mentionJpa.saveAll(mentions.map { CommentMentionEntity.fromDomain(it) })
    }

    override fun deleteAllMentionsByCommentId(commentId: Long) {
        mentionJpa.deleteByCommentId(commentId)
    }

    override fun findMentionsByCommentIds(commentIds: List<Long>): List<CommentMention> =
        if (commentIds.isEmpty()) emptyList()
        else mentionJpa.findAllByCommentIdIn(commentIds).map { it.toDomain() }

    // ── Suggestions ────────────────────────────────────────────
    override fun saveSuggestion(suggestion: CommentSuggestion): CommentSuggestion {
        val entity = suggestionJpa.findById(suggestion.id ?: -1).orElse(null)?.also {
            // 변경 가능 필드만 갱신 (불변 필드 보호)
            it.status = suggestion.status
            it.updatedAt = suggestion.updatedAt
        } ?: CommentSuggestionEntity.fromDomain(suggestion)
        return suggestionJpa.save(entity).toDomain()
    }

    override fun findSuggestionByCommentId(commentId: Long): CommentSuggestion? =
        suggestionJpa.findByCommentId(commentId)?.toDomain()

    override fun findSuggestionsByCommentIds(commentIds: List<Long>): List<CommentSuggestion> =
        if (commentIds.isEmpty()) emptyList()
        else suggestionJpa.findAllByCommentIdIn(commentIds).map { it.toDomain() }

    override fun deleteSuggestionByCommentId(commentId: Long) {
        suggestionJpa.deleteByCommentId(commentId)
    }
}
