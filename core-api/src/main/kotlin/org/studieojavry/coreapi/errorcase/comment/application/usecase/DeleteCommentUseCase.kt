package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort

/**
 * 댓글 삭제 — 작성자 본인만. **soft delete**: body 는 placeholder("(삭제된 본문)") 로,
 * deletedAt 세팅. 답글이 달려있어도 부모는 [삭제된 댓글] placeholder 형태로 유지.
 * 멱등 — 이미 삭제된 댓글은 no-op.
 */
@Service
class DeleteCommentUseCase(
    private val commentRepository: CommentRepositoryPort,
) {
    @Transactional
    fun invoke(commentId: Long, requesterUserId: Long) {
        val c = commentRepository.findById(commentId)
            ?: throw CommentNotFoundException(commentId)
        if (c.isDeleted) return
        if (c.authorUserId != requesterUserId) {
            throw CommentAccessDeniedException("only the author can delete comment $commentId")
        }
        // 부수 정리 먼저(bulk @Modifying + clearAutomatically=true 가 persistence context 를 비우므로
        // softDelete save 는 **반드시 마지막**에 호출해야 UPDATE 가 누락되지 않는다).
        commentRepository.deleteAllReactionsByCommentId(commentId)
        commentRepository.deleteAllHelpfulByCommentId(commentId)
        commentRepository.deleteAllMentionsByCommentId(commentId)
        commentRepository.deleteSuggestionByCommentId(commentId)
        c.softDelete()
        commentRepository.save(c)
    }
}
