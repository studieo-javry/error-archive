package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.command.CreateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentMention
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort

/**
 * 댓글 작성. 정책:
 *  - 권한: 케이스 read 권한(`ErrorCaseAccess.requireRead`) — 댓글은 토론 도구라 READ 권한자도 가능.
 *  - depth=1 강제: parent 가 답글이면 *그 부모의 부모(top-level)* 로 자동 redirect.
 *  - 인용 검증: STEP 의 source 가 같은 케이스 소속인지 확인. (본문 인용은 errorCaseId 자체가 source)
 *  - snapshot 자동 truncate (도메인 Comment.create 가 처리).
 *  - @멘션 파싱 → CommentMention 적재(알림 라우팅용).
 *  - Diff 제안(GitHub Suggest 차용) 동시 첨부 — 한 댓글에 최대 1개. sourceId 는 opaque.
 */
@Service
class CreateCommentUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val access: ErrorCaseAccess,
) {
    @Transactional
    fun invoke(command: CreateCommentCommand): Comment {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
        access.requireRead(errorCase, command.authorUserId)

        // depth=1 강제 — parent 가 답글이면 부모의 부모로 redirect
        val resolvedParentId = command.parentCommentId?.let { resolveTopLevelParent(it, command.errorCaseId) }

        // 인용 검증
        validateQuoteSource(command, errorCaseId = command.errorCaseId)

        val comment = Comment.create(
            errorCaseId = command.errorCaseId,
            authorUserId = command.authorUserId,
            parentCommentId = resolvedParentId,
            body = command.body,
            quoteSourceKind = command.quoteSourceKind,
            quoteSourceId = command.quoteSourceId,
            quoteSnapshot = command.quoteSnapshot,
        )
        val saved = commentRepository.save(comment)

        // @멘션 파싱 (한글/영문/숫자/_./- 허용)
        val mentions = MENTION_PATTERN.findAll(command.body)
            .map { it.groupValues[1] }
            .distinct()
            .take(MENTIONS_MAX)
            .map { CommentMention.create(saved.id!!, it) }
            .toList()
        commentRepository.saveMentions(mentions)

        // Diff 제안 첨부 (있으면)
        command.suggestion?.let { s ->
            val suggestion = CommentSuggestion.create(
                commentId = saved.id!!,
                sourceType = s.sourceType,
                sourceId = s.sourceId,
                startLine = s.startLine,
                endLine = s.endLine,
                oldCode = s.oldCode,
                newCode = s.newCode,
            )
            commentRepository.saveSuggestion(suggestion)
        }

        return saved
    }

    /** parent 가 답글이면 그 부모의 부모(=top-level) 로 따라간다. 다른 케이스의 댓글이면 400. */
    private fun resolveTopLevelParent(parentId: Long, errorCaseId: Long): Long {
        var cursor = commentRepository.findById(parentId)
            ?: throw CommentInvalidException("parent comment not found: $parentId")
        if (cursor.errorCaseId != errorCaseId) {
            throw CommentInvalidException("parent comment belongs to a different case")
        }
        while (cursor.parentCommentId != null) {
            cursor = commentRepository.findById(cursor.parentCommentId!!)
                ?: throw CommentInvalidException("orphan parent chain")
        }
        return cursor.id!!
    }

    private fun validateQuoteSource(command: CreateCommentCommand, errorCaseId: Long) {
        when (command.quoteSourceKind) {
            QuoteSourceKind.NONE -> { /* no-op */ }
            QuoteSourceKind.CASE_BODY -> {
                // CASE_BODY 는 errorCaseId 가 곧 source — snapshot 만 필수
                require(command.quoteSnapshot?.isNotBlank() == true) {
                    "CASE_BODY quote must include a snapshot"
                }
            }
            QuoteSourceKind.STEP -> {
                val stepId = command.quoteSourceId
                    ?: throw CommentInvalidException("STEP quote requires quoteSourceId(step id)")
                val step = stepRepository.findById(stepId)
                    ?: throw CommentInvalidException("step not found: $stepId")
                if (step.errorCaseId != errorCaseId) {
                    throw CommentInvalidException("step $stepId is not part of case $errorCaseId")
                }
            }
        }
    }

    companion object {
        private val MENTION_PATTERN = Regex("@([A-Za-z0-9_.가-힣-]+)")
        private const val MENTIONS_MAX = 20
    }
}

/** 댓글 입력/인용 검증 실패. → 400. */
class CommentInvalidException(message: String) : RuntimeException(message)
