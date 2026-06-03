package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.comment.application.command.UpdateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment

/** 댓글 수정 — 작성자 본인만. body 만 변경. editedAt 자동 세팅(도메인). 삭제된 댓글은 수정 불가. */
@Service
class UpdateCommentUseCase(
    private val commentRepository: CommentRepositoryPort,
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
        return commentRepository.save(c)
    }
}

class CommentNotFoundException(val commentId: Long) : RuntimeException("comment not found: $commentId")
class CommentAccessDeniedException(message: String) : RuntimeException(message)
