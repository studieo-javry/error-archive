package org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort.AuthorSummary
import org.studieojavry.coreapi.errorcase.comment.application.usecase.ListCommentsUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.ReactionAggregate
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentSuggestion
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionSourceType
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.SuggestionStatus
import java.time.LocalDateTime

@Schema(description = "댓글 응답(트리). top-level + replies(depth=1).")
data class CommentResponse(
    val id: Long,
    val errorCaseId: Long,
    @field:Schema(description = "작성자 userId. 표시용 이름은 `author` 사용 — 이 값은 식별/딥링크용.")
    val authorUserId: Long,
    @field:Schema(description = "작성자 프로필(표시 이름 + handle + 아바타). iam 조회 실패/탈퇴 시 displayName='알 수 없는 사용자'.")
    val author: CommentAuthorDto,
    @field:Schema(description = "케이스 owner 와 일치하면 true — 프런트가 [작성자] 배지 렌더 결정")
    val isAuthorOfCase: Boolean,
    val parentCommentId: Long?,
    val body: String,
    val quote: QuoteDto?,
    val reactions: List<ReactionDto>,
    val helpful: HelpfulDto,
    val mentions: List<String>,
    @field:Schema(description = "Diff 제안. 없으면 null.")
    val suggestion: SuggestionDto?,
    val editedAt: LocalDateTime?,
    val deletedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val replies: List<CommentResponse>,
) {
    @Schema(description = "인용 정보. NONE 이면 응답에서 null.")
    data class QuoteDto(
        val kind: QuoteSourceKind,
        val sourceId: Long?,
        val snapshot: String?,
        val truncated: Boolean,
    )

    @Schema(description = "이모지 리액션 집계 (emoji 별).")
    data class ReactionDto(
        val emoji: String,
        val count: Int,
        val userIds: List<Long>,
        val reactedByMe: Boolean,
    )

    @Schema(description = "도움됨 집계 — count + 누른 사용자 userId 목록 + 내가 눌렀는지.")
    data class HelpfulDto(
        val count: Int,
        val userIds: List<Long>,
        val helpedByMe: Boolean,
    )

    @Schema(description = "Diff 제안 — GitHub Suggest 스타일. 댓글 1:1.")
    data class SuggestionDto(
        val id: Long,
        val sourceType: SuggestionSourceType,
        val sourceId: String,
        val startLine: Int,
        val endLine: Int,
        val oldCode: String,
        val newCode: String,
        val status: SuggestionStatus,
        val additions: Int,
        val deletions: Int,
        @field:Schema(description = "렌더링용 라인 단위 unified diff (del+add only — 단순 line 치환).")
        val lines: List<DiffLineDto>,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    )

    @Schema(description = "Diff 라인 — type=del/add. ctx 는 단순 라인 치환이라 없음.")
    data class DiffLineDto(
        val type: String,         // "del" | "add"
        val oldNo: Int?,
        val newNo: Int?,
        val code: String,
    )
}

@Schema(description = "댓글 작성자/참여자 표시 정보. userId → 표시 이름/handle/아바타 해석용.")
data class CommentAuthorDto(
    val userId: Long,
    @field:Schema(description = "GitHub login 매핑 unique handle. 탈퇴/조회 실패 시 null.")
    val handle: String?,
    @field:Schema(description = "표시 이름. 조회 실패/탈퇴 시 '알 수 없는 사용자'.")
    val displayName: String,
    val avatarUrl: String?,
) {
    companion object {
        fun from(a: AuthorSummary) = CommentAuthorDto(
            userId = a.userId,
            handle = a.handle,
            displayName = a.displayName,
            avatarUrl = a.avatarUrl,
        )

        /** iam 에서 해석 못 한 userId(탈퇴/비활성) 폴백 — userId 노출 대신 익명 표기. */
        fun unknown(userId: Long) = CommentAuthorDto(
            userId = userId,
            handle = null,
            displayName = "알 수 없는 사용자",
            avatarUrl = null,
        )
    }
}

@Schema(description = "댓글 목록 + cursor 페이징 + 인용 위치 집계.")
data class CommentListResponse(
    val items: List<CommentResponse>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val quoteSummary: QuoteSummaryDto,
    @field:Schema(description = "이 목록에 등장하는 모든 userId(댓글 작성자 + 도움됨/리액션 누른 사람)의 표시 정보 디렉토리. 프런트가 helpful/reaction 팝오버 이름 해석에 사용.")
    val authors: List<CommentAuthorDto> = emptyList(),
) {
    @Schema(description = "인용 위치별 집계 (사이드 패널 렌더링용).")
    data class QuoteSummaryDto(
        val general: Int,
        val caseBody: Int,
        val steps: List<KeyCountDto>,
    )
    data class KeyCountDto(val sourceId: Long, val count: Int)
}

@Schema(description = "리액션 토글 응답 — 해당 이모지의 최신 집계.")
data class ReactionResponse(
    val emoji: String,
    val count: Int,
    val userIds: List<Long>,
    val reactedByMe: Boolean,
)

@Schema(description = "도움됨 토글 응답.")
data class HelpfulToggleResponse(
    val helpedByMe: Boolean,
    val count: Int,
    val userIds: List<Long>,
)

@Schema(description = "Diff 제안 상태 변경 응답.")
data class SuggestionStatusResponse(
    val commentId: Long,
    val status: SuggestionStatus,
    val updatedAt: LocalDateTime,
)

// ── mappers ──────────────────────────────────────────────────────────────────────
internal fun Comment.toQuoteDto(): CommentResponse.QuoteDto? =
    if (quoteSourceKind == QuoteSourceKind.NONE) null
    else CommentResponse.QuoteDto(
        kind = quoteSourceKind,
        sourceId = quoteSourceId,
        snapshot = quoteSnapshot,
        truncated = quoteSnapshotTruncated,
    )

internal fun ReactionAggregate.toDto(): CommentResponse.ReactionDto =
    CommentResponse.ReactionDto(emoji, count, userIds, reactedByMe)

/**
 * 단순 line-단위 unified diff 렌더 행 — HTML 의 buildDiff 와 동일 패턴.
 * old 전체 del 후 new 전체 add. 진짜 LCS diff 가 아니라 *표시용*.
 * (ctx 행은 만들지 않는다 — 라인 매핑이 모호하므로 단순/명시적으로 가는 게 안전)
 */
internal fun CommentSuggestion.toDto(): CommentResponse.SuggestionDto {
    val oldLines = if (oldCode.isEmpty()) emptyList() else oldCode.split('\n')
    val newLines = if (newCode.isEmpty()) emptyList() else newCode.split('\n')
    val rows = mutableListOf<CommentResponse.DiffLineDto>()
    oldLines.forEachIndexed { i, code ->
        rows += CommentResponse.DiffLineDto(type = "del", oldNo = startLine + i, newNo = null, code = code)
    }
    newLines.forEachIndexed { i, code ->
        rows += CommentResponse.DiffLineDto(type = "add", oldNo = null, newNo = startLine + i, code = code)
    }
    return CommentResponse.SuggestionDto(
        id = id!!,
        sourceType = sourceType, sourceId = sourceId,
        startLine = startLine, endLine = endLine,
        oldCode = oldCode, newCode = newCode,
        status = status,
        additions = additions, deletions = deletions,
        lines = rows,
        createdAt = createdAt, updatedAt = updatedAt,
    )
}

internal fun ListCommentsUseCase.CommentNode.toResponse(
    caseOwnerUserId: Long,
    authors: Map<Long, CommentAuthorDto>,
): CommentResponse =
    CommentResponse(
        id = comment.id!!,
        errorCaseId = comment.errorCaseId,
        authorUserId = comment.authorUserId,
        author = authors[comment.authorUserId] ?: CommentAuthorDto.unknown(comment.authorUserId),
        isAuthorOfCase = comment.authorUserId == caseOwnerUserId,
        parentCommentId = comment.parentCommentId,
        body = comment.body,
        quote = comment.toQuoteDto(),
        reactions = reactions.map { it.toDto() },
        helpful = CommentResponse.HelpfulDto(helpedByCount, helpedByUserIds, helpedByMe),
        mentions = mentions,
        suggestion = suggestion?.toDto(),
        editedAt = comment.editedAt,
        deletedAt = comment.deletedAt,
        createdAt = comment.createdAt,
        updatedAt = comment.updatedAt,
        replies = replies.map { it.toResponse(caseOwnerUserId, authors) },
    )
