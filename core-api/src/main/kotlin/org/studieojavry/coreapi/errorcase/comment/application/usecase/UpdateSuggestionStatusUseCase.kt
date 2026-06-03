package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.command.UpdateSuggestionStatusCommand
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion

/**
 * Diff 제안의 상태 변경 (PENDING ↔ APPLIED / REJECTED).
 * 권한: **케이스 owner 만** (GitHub PR author 와 동치 — *제안 수용 권한* 은 코드 소유자).
 * 댓글 작성자는 PENDING 으로 제안만 하고, 직접 상태를 바꿀 수 없다.
 */
@Service
class UpdateSuggestionStatusUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
) {
    @Transactional
    fun invoke(command: UpdateSuggestionStatusCommand): CommentSuggestion {
        val comment = commentRepository.findById(command.commentId)
            ?: throw CommentNotFoundException(command.commentId)
        val errorCase = errorCaseRepository.findById(comment.errorCaseId)
            ?: throw ErrorCaseNotFoundException(comment.errorCaseId)
        if (errorCase.ownerUserId != command.requesterUserId) {
            throw CommentAccessDeniedException(
                "only the case owner can change suggestion status of comment ${command.commentId}"
            )
        }
        val suggestion = commentRepository.findSuggestionByCommentId(command.commentId)
            ?: throw CommentInvalidException("no diff suggestion attached to comment ${command.commentId}")

        suggestion.changeStatus(command.newStatus)
        return commentRepository.saveSuggestion(suggestion)
    }
}
