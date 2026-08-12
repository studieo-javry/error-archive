package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentHelpful
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.Instant

/**
 * "도움됨" 토글 — **모든 사용자가 가능**(작성자 제한 X). 케이스 read 권한만 있으면 OK.
 * 이미 표시된 상태면 해제, 아니면 표시. 멱등성은 DB UNIQUE(commentId, userId) 가 보장.
 */
@Service
class ToggleHelpfulUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val access: ErrorCaseAccess,
    private val activityEventPublisher: ActivityEventPublisherPort,
) {
    @Transactional
    fun invoke(commentId: Long, requesterUserId: Long): Result {
        val c = commentRepository.findById(commentId)
            ?: throw CommentNotFoundException(commentId)
        if (c.isDeleted) throw CommentInvalidException("cannot toggle helpful on a deleted comment")

        val errorCase = errorCaseRepository.findById(c.errorCaseId)
            ?: throw ErrorCaseNotFoundException(c.errorCaseId)
        access.requireRead(errorCase, requesterUserId)

        val existing = commentRepository.findHelpfulByCommentIds(listOf(commentId))
        val mineExists = existing.any { it.userId == requesterUserId }
        if (mineExists) {
            commentRepository.deleteHelpful(commentId, requesterUserId)
        } else {
            commentRepository.saveHelpful(CommentHelpful.create(commentId, requesterUserId))
            // 잔디용 activity event — *추가될 때만* 발행 (해제 시엔 잔디 변경 없음)
            activityEventPublisher.publish(
                ActivityEventPublisherPort.ActivityEvent(
                    userId = requesterUserId,
                    type = ActivityEventPublisherPort.Type.COMMENT_HELPFUL,
                    occurredAt = Instant.now(),
                    idempotencyKey = "helpful:$commentId:$requesterUserId",
                    meta = mapOf("commentId" to commentId),
                )
            )
        }
        val after = commentRepository.findHelpfulByCommentIds(listOf(commentId))
        return Result(
            helpedByMe = !mineExists,
            count = after.size,
            userIds = after.map { it.userId },
        )
    }

    data class Result(val helpedByMe: Boolean, val count: Int, val userIds: List<Long>)
}
