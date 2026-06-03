package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentReaction
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * 이모지 리액션 추가 — Slack 스타일. 같은 사용자가 같은 이모지를 또 누르면 멱등(UNIQUE 위반 → no-op).
 * 권한: 케이스 read.
 */
@Service
class AddReactionUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(commentId: Long, requesterUserId: Long, emoji: String): ReactionAggregate {
        val c = commentRepository.findById(commentId)
            ?: throw CommentNotFoundException(commentId)
        if (c.isDeleted) throw CommentInvalidException("cannot react to a deleted comment")

        val errorCase = errorCaseRepository.findById(c.errorCaseId)
            ?: throw ErrorCaseNotFoundException(c.errorCaseId)
        access.requireRead(errorCase, requesterUserId)

        val all = commentRepository.findReactionsByCommentIds(listOf(commentId))
        val alreadyMine = all.any { it.userId == requesterUserId && it.emoji == emoji }
        if (!alreadyMine) {
            commentRepository.saveReaction(CommentReaction.create(commentId, requesterUserId, emoji))
        }
        val after = commentRepository.findReactionsByCommentIds(listOf(commentId)).filter { it.emoji == emoji }
        return ReactionAggregate(
            emoji = emoji,
            count = after.size,
            userIds = after.map { it.userId },
            reactedByMe = after.any { it.userId == requesterUserId },
        )
    }
}

/** 이모지 리액션 해제. 본인 것만. */
@Service
class RemoveReactionUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(commentId: Long, requesterUserId: Long, emoji: String): ReactionAggregate {
        val c = commentRepository.findById(commentId)
            ?: throw CommentNotFoundException(commentId)
        val errorCase = errorCaseRepository.findById(c.errorCaseId)
            ?: throw ErrorCaseNotFoundException(c.errorCaseId)
        access.requireRead(errorCase, requesterUserId)

        commentRepository.deleteReaction(commentId, requesterUserId, emoji)
        val after = commentRepository.findReactionsByCommentIds(listOf(commentId)).filter { it.emoji == emoji }
        return ReactionAggregate(
            emoji = emoji,
            count = after.size,
            userIds = after.map { it.userId },
            reactedByMe = after.any { it.userId == requesterUserId },
        )
    }
}

data class ReactionAggregate(
    val emoji: String,
    val count: Int,
    val userIds: List<Long>,
    val reactedByMe: Boolean,
)
