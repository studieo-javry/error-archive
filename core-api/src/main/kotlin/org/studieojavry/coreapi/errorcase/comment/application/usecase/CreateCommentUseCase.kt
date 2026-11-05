package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.command.CreateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator
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
    private val mentionResolver: MentionResolver,
    private val notificationPublisher: NotificationPublisherPort,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val cacheInvalidator: DashboardCacheInvalidator,
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

        // @멘션 파싱 + iam-api 로 식별자 → userId 매핑
        val mentions = mentionResolver.resolve(saved.id!!, command.body)
        commentRepository.saveMentions(mentions)

        // 알림 발사 — userId 가 매핑됐고 본인 멘션 아니고 *케이스를 읽을 수 있는* 경우만.
        // (워크스페이스/비공개 케이스에서 권한 없는 사람을 멘션해도 스니펫이 새지 않게.) 발송 실패는 댓글 작성에 영향 X.
        val recipients = mentions
            .mapNotNull { it.mentionedUserId }
            .filter { it != command.authorUserId }
            .distinct()
            .filter { access.canUserRead(errorCase, it) }
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

        // watchlist 자동 등록은 하지 않음 — 등록 여부는 사용자가 POST /error-cases/{id}/watchlist 로 직접 선택.
        // 캐시 무효화 — case owner 의 recent-active-cases + 작성자의 my-recent-activities
        // (watcher 들의 watchlist-feed 는 30초 TTL 자연 만료에 의존)
        runCatching { cacheInvalidator.evictRecentActive(errorCase.ownerUserId) }
        runCatching { cacheInvalidator.evictMyRecentActivities(command.authorUserId) }

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
}

/** 댓글 입력/인용 검증 실패. → 400. */
class CommentInvalidException(message: String) : RuntimeException(message)
