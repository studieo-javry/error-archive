package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import java.time.LocalDateTime
import java.util.Base64

/**
 * 댓글 목록 — top-level 만 cursor 페이징하고 답글은 트리 children 으로 포함.
 *  - 정렬: NEWEST(createdAt DESC) / OLDEST(ASC) / HELPFUL(도움됨 count DESC, tie 면 newest)
 *  - 필터: ALL / GENERAL(NONE) / CASE_BODY / STEP — top-level 에만 적용. 답글은 부모와 함께.
 *  - 응답: items[] + nextCursor + hasNext + quoteSummary(실시간 집계).
 *  - 삭제된 댓글: deletedAt != null 인 것도 반환(프런트가 placeholder 렌더).
 *
 * cursor 인코딩은 `<createdAt isoString>|<id>` 의 Base64URL. ListErrorCases 와 동일 패턴.
 */
@Service
class ListCommentsUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val access: ErrorCaseAccess,
) {

    enum class Sort { NEWEST, OLDEST, HELPFUL }

    data class Input(
        val errorCaseId: Long,
        val requesterUserId: Long,
        val sort: Sort = Sort.NEWEST,
        val quoteKindFilter: QuoteKindFilter = QuoteKindFilter.ALL,
        val cursor: String? = null,
        val size: Int = 20,
    )

    enum class QuoteKindFilter { ALL, GENERAL, CASE_BODY, STEP }

    data class Result(
        val items: List<CommentNode>,
        val nextCursor: String?,
        val hasNext: Boolean,
        val quoteSummary: QuoteSummary,
    )

    data class CommentNode(
        val comment: Comment,
        val reactions: List<ReactionAggregate>,
        val helpedByCount: Int,
        val helpedByUserIds: List<Long>,
        val helpedByMe: Boolean,
        val mentions: List<String>,
        val suggestion: CommentSuggestion?,
        val replies: List<CommentNode>,
    )

    data class QuoteSummary(
        val general: Int,
        val caseBody: Int,
        val steps: List<KeyCount>,
    )

    data class KeyCount(val sourceId: Long, val count: Int)

    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val errorCase = errorCaseRepository.findById(input.errorCaseId)
            ?: throw ErrorCaseNotFoundException(input.errorCaseId)
        access.requireRead(errorCase, input.requesterUserId)

        val all = commentRepository.findAllByErrorCaseId(input.errorCaseId)
        val topLevel = all.filter { it.parentCommentId == null }.filter { passesFilter(it, input.quoteKindFilter) }
        val replies = all.filter { it.parentCommentId != null }

        val size = input.size.coerceIn(1, MAX_SIZE)

        // 정렬
        val sorted = when (input.sort) {
            Sort.NEWEST -> topLevel.sortedWith(compareByDescending<Comment> { it.createdAt }.thenByDescending { it.id })
            Sort.OLDEST -> topLevel.sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
            Sort.HELPFUL -> {
                val helpfulCount = commentRepository
                    .findHelpfulByCommentIds(topLevel.mapNotNull { it.id })
                    .groupingBy { it.commentId }.eachCount()
                topLevel.sortedWith(
                    compareByDescending<Comment> { helpfulCount[it.id] ?: 0 }
                        .thenByDescending { it.createdAt }.thenByDescending { it.id }
                )
            }
        }

        // cursor 적용 (정렬이 NEWEST/OLDEST 일 때만 의미; HELPFUL 은 cursor 미지원 — 전체 반환)
        val afterCursor = applyCursor(sorted, input.cursor, input.sort)

        val page = afterCursor.take(size + 1)
        val hasNext = page.size > size
        val items = if (hasNext) page.dropLast(1) else page

        // 응답에 필요한 부속 데이터(reactions/helpful/mentions) 를 *한 번에* 조회 (N+1 회피)
        val pageIds = items.mapNotNull { it.id }
        val childIds = replies.filter { it.parentCommentId in pageIds }.mapNotNull { it.id }
        val allIds = pageIds + childIds

        val reactions = commentRepository.findReactionsByCommentIds(allIds).groupBy { it.commentId }
        val helpful = commentRepository.findHelpfulByCommentIds(allIds).groupBy { it.commentId }
        val mentions = commentRepository.findMentionsByCommentIds(allIds).groupBy { it.commentId }
        val suggestions = commentRepository.findSuggestionsByCommentIds(allIds).associateBy { it.commentId }

        fun buildNode(c: Comment): CommentNode {
            val rxs = (reactions[c.id] ?: emptyList())
                .groupBy { it.emoji }
                .map { (em, lst) -> ReactionAggregate(
                    emoji = em, count = lst.size,
                    userIds = lst.map { it.userId },
                    reactedByMe = lst.any { it.userId == input.requesterUserId },
                ) }
                .sortedByDescending { it.count }
            val hps = helpful[c.id] ?: emptyList()
            val mnts = mentions[c.id] ?: emptyList()
            return CommentNode(
                comment = c,
                reactions = rxs,
                helpedByCount = hps.size,
                helpedByUserIds = hps.map { it.userId },
                helpedByMe = hps.any { it.userId == input.requesterUserId },
                mentions = mnts.map { it.mentionedIdentifier },
                suggestion = suggestions[c.id],
                replies = emptyList(),
            )
        }

        val nodes = items.map { top ->
            val replyNodes = replies
                .filter { it.parentCommentId == top.id }
                .sortedBy { it.createdAt }   // 답글은 항상 시간순(흐름)
                .map { buildNode(it) }
            buildNode(top).copy(replies = replyNodes)
        }

        val nextCursor = when {
            !hasNext || input.sort == Sort.HELPFUL -> null
            else -> {
                val last = items.last()
                encodeCursor(last.createdAt, last.id!!)
            }
        }

        // quoteSummary 는 전체 댓글 기준(삭제 안 된 것), 필터 무관
        val alive = all.filter { !it.isDeleted }
        val general = alive.count { it.quoteSourceKind == QuoteSourceKind.NONE }
        val caseBody = alive.count { it.quoteSourceKind == QuoteSourceKind.CASE_BODY }
        val steps = alive.filter { it.quoteSourceKind == QuoteSourceKind.STEP && it.quoteSourceId != null }
            .groupingBy { it.quoteSourceId!! }.eachCount()
            .map { (id, n) -> KeyCount(id, n) }
            .sortedByDescending { it.count }

        return Result(
            items = nodes,
            nextCursor = nextCursor,
            hasNext = hasNext,
            quoteSummary = QuoteSummary(general, caseBody, steps),
        )
    }

    private fun passesFilter(c: Comment, f: QuoteKindFilter): Boolean = when (f) {
        QuoteKindFilter.ALL -> true
        QuoteKindFilter.GENERAL -> c.quoteSourceKind == QuoteSourceKind.NONE
        QuoteKindFilter.CASE_BODY -> c.quoteSourceKind == QuoteSourceKind.CASE_BODY
        QuoteKindFilter.STEP -> c.quoteSourceKind == QuoteSourceKind.STEP
    }

    private fun applyCursor(items: List<Comment>, cursor: String?, sort: Sort): List<Comment> {
        if (cursor.isNullOrBlank() || sort == Sort.HELPFUL) return items
        val decoded = runCatching { decodeCursor(cursor) }.getOrNull() ?: return items
        val (ct, cid) = decoded
        return when (sort) {
            Sort.NEWEST -> items.filter {
                it.createdAt < ct || (it.createdAt == ct && (it.id ?: 0) < cid)
            }
            Sort.OLDEST -> items.filter {
                it.createdAt > ct || (it.createdAt == ct && (it.id ?: 0) > cid)
            }
            else -> items
        }
    }

    private fun encodeCursor(at: LocalDateTime, id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$at|$id".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<LocalDateTime, Long> {
        val raw = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val sep = raw.lastIndexOf('|')
        val at = LocalDateTime.parse(raw.substring(0, sep))
        val id = raw.substring(sep + 1).toLong()
        return at to id
    }

    companion object {
        private const val MAX_SIZE = 100
    }
}
