package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.application.command.UpdateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * 댓글 수정 — 작성자 본인만. body 만 변경. editedAt 자동 세팅(도메인). 삭제된 댓글은 수정 불가.
 *
 * 멘션은 수정된 본문 기준으로 다시 적재하고, *이번 수정으로 새로 멘션된* 사람에게만 알림을 보낸다
 * (오타 고치느라 저장할 때마다 기존 멘션 대상에게 알림이 반복되지 않게).
 */
@Service
class UpdateCommentUseCase(
    private val commentRepository: CommentRepositoryPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val access: ErrorCaseAccess,
    private val mentionResolver: MentionResolver,
    private val notificationPublisher: NotificationPublisherPort,
) {
    @Transactional
    fun invoke(command: UpdateCommentCommand): Comment {
        val c = commentRepository.findById(command.commentId)
            ?: throw CommentNotFoundException(command.commentId)
        if (c.isDeleted) throw CommentInvalidException("cannot edit a deleted comment")
        if (c.authorUserId != command.requesterUserId) {
            throw CommentAccessDeniedException("only the author can edit comment ${command.commentId}")
        }
        c.edit(command.body)
        val saved = commentRepository.save(c)

        val before = commentRepository.findMentionsByCommentIds(listOf(saved.id!!))
            .mapNotNull { it.mentionedUserId }
            .toSet()
        val mentions = mentionResolver.resolve(saved.id!!, command.body)
        commentRepository.deleteAllMentionsByCommentId(saved.id!!)
        commentRepository.saveMentions(mentions)

        // 새로 멘션된 사람만 — 본인 제외, 케이스 읽기 권한자만. 발송 실패는 수정에 영향 X.
        val errorCase = errorCaseRepository.findById(saved.errorCaseId)
        val recipients = mentions
            .mapNotNull { it.mentionedUserId }
            .filter { it != command.requesterUserId && it !in before }
            .distinct()
            .filter { uid -> errorCase != null && access.canRead(errorCase, uid) }
        if (recipients.isNotEmpty()) {
            runCatching {
                notificationPublisher.publishMentions(
                    NotificationPublisherPort.MentionEvent(
                        recipientUserIds = recipients,
                        actorUserId = command.requesterUserId,
                        errorCaseId = saved.errorCaseId,
                        commentId = saved.id!!,
                        snippet = command.body.take(140),
                    )
                )
            }
        }
        return saved
    }
}

class CommentNotFoundException(val commentId: Long) : RuntimeException("comment not found: $commentId")
class CommentAccessDeniedException(message: String) : RuntimeException(message)
