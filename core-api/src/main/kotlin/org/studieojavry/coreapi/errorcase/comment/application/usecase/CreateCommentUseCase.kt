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
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import java.time.Instant

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
    private val iamUserQuery: IamUserQueryPort,
    private val notificationPublisher: NotificationPublisherPort,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val caseWatchlistRepository:
        org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort,
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

        // @멘션 파싱 (한글/영문/숫자/_./- 허용) + iam-api 로 식별자 → userId 매핑
        val identifiers = MENTION_PATTERN.findAll(command.body)
            .map { it.groupValues[1] }
            .distinct()
            .take(MENTIONS_MAX)
            .toList()
        val mentions = identifiers.map { ident ->
            val uid = runCatching { iamUserQuery.resolveByDisplayName(ident) }.getOrNull()
            CommentMention.create(saved.id!!, ident, uid)
        }
        commentRepository.saveMentions(mentions)

        // 알림 발사 — userId 가 매핑됐고 본인 멘션 아닌 경우만. 발송 실패는 댓글 작성에 영향 X.
        val recipients = mentions
            .mapNotNull { it.mentionedUserId }
            .filter { it != command.authorUserId }
            .distinct()
        if (recipients.isNotEmpty()) {
            runCatching {
                notificationPublisher.publishMentions(
                    NotificationPublisherPort.MentionEvent(
                        recipientUserIds = recipients,
                        actorUserId = command.authorUserId,
                        errorCaseId = command.errorCaseId,
                        commentId = saved.id!!,
                        snippet = command.body.take(140),
                    )
                )
            }
        }

        // 답글 알림 — *원래 사용자가 답글 대상으로 지정한* parent comment 의 author 에게.
        // depth=1 강제로 resolvedParentId 는 top-level 로 바뀔 수 있지만, 알림 대상은
        // *사용자가 답글하려 했던 그 댓글* 의 author 가 자연스러움.
        // 스킵 조건: (a) 본인의 댓글에 답글 (b) 이미 mention 으로 알림 가는 경우 (중복 방지)
        command.parentCommentId?.let { originalParentId ->
            val parentAuthor = runCatching { commentRepository.findById(originalParentId)?.authorUserId }.getOrNull()
            if (parentAuthor != null &&
                parentAuthor != command.authorUserId &&
                parentAuthor !in recipients
            ) {
                runCatching {
                    notificationPublisher.publishReplies(
                        NotificationPublisherPort.ReplyEvent(
                            recipientUserId = parentAuthor,
                            actorUserId = command.authorUserId,
                            errorCaseId = command.errorCaseId,
                            commentId = saved.id!!,
                            parentCommentId = originalParentId,
                            snippet = command.body.take(140),
                        )
                    )
                }
            }
        }

        // 내 글 댓글 알림 — *최상위* 댓글 (parentCommentId == null) 한정으로 ErrorCase author 에게.
        // 답글이면 reply 알림이 책임지므로 skip. 스킵 조건:
        //  (a) 본인 글에 본인 댓글  (b) mention recipient 에 owner 이미 포함  (c) 답글이면 발화 안 함
        if (command.parentCommentId == null &&
            errorCase.ownerUserId != command.authorUserId &&
            errorCase.ownerUserId !in recipients
        ) {
            runCatching {
                notificationPublisher.publishCommentOnErrorCase(
                    NotificationPublisherPort.CommentOnErrorCaseEvent(
                        recipientUserId = errorCase.ownerUserId,
                        actorUserId = command.authorUserId,
                        errorCaseId = command.errorCaseId,
                        commentId = saved.id!!,
                        snippet = command.body.take(140),
                    )
                )
            }
        }

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

        // 잔디용 activity event 발행 (fire-and-forget). 댓글 자체 발행만 가산 — 멘션·답글 가산 가중치 정책은 yml.
        activityEventPublisher.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = command.authorUserId,
                type = ActivityEventPublisherPort.Type.COMMENT_POSTED,
                occurredAt = Instant.now(),
                idempotencyKey = "comment:${saved.id}",
                meta = mapOf("errorCaseId" to command.errorCaseId),
            )
        )

        // 자동 watchlist — "발 담군" 사용자를 향후 활동 알림 수신자로 (멱등). owner 본인은 옵션이지만 ok.
        runCatching { caseWatchlistRepository.add(command.errorCaseId, command.authorUserId) }

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
        // @ 앞이 *문자열 시작* 또는 *공백류* 일 때만 멘션으로 인정.
        // 예) "hello@user" / "`@user" / "me@example.com" → 멘션 아님 (이메일·코드 안의 @ 보호)
        //     "@user", " @user", "\n@user" → 멘션
        // `(?:^|\s)` 의 \s 1 글자는 consumed 되지만, findAll 은 그 다음 인덱스부터 재시도하므로
        // 연속 멘션도 정상 인식한다(예: "@a @b").
        private val MENTION_PATTERN = Regex("(?:^|\\s)@([A-Za-z0-9_.가-힣-]+)")
        private const val MENTIONS_MAX = 20
    }
}

/** 댓글 입력/인용 검증 실패. → 400. */
class CommentInvalidException(message: String) : RuntimeException(message)
