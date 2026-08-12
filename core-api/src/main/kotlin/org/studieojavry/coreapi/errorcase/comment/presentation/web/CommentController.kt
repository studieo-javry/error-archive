package org.studieojavry.coreapi.errorcase.comment.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.command.CreateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.command.UpdateCommentCommand
import org.studieojavry.coreapi.errorcase.comment.application.command.UpdateSuggestionStatusCommand
import org.studieojavry.coreapi.errorcase.comment.application.usecase.AddReactionUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.CommentAccessDeniedException
import org.studieojavry.coreapi.errorcase.comment.application.usecase.CommentInvalidException
import org.studieojavry.coreapi.errorcase.comment.application.usecase.CommentNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.usecase.CreateCommentUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.DeleteCommentUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.ListCommentsUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.RemoveReactionUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.ToggleHelpfulUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.UpdateCommentUseCase
import org.studieojavry.coreapi.errorcase.comment.application.usecase.UpdateSuggestionStatusUseCase
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.request.AddReactionRequest
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.request.CreateCommentRequest
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.request.UpdateCommentRequest
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.request.UpdateSuggestionStatusRequest
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.CommentListResponse
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.CommentResponse
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.HelpfulToggleResponse
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.ReactionResponse
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.SuggestionStatusResponse
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.toDto
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.toQuoteDto
import org.studieojavry.coreapi.errorcase.comment.presentation.web.dto.response.toResponse

@Tag(
    name = "error-case-comments",
    description = """
        에러케이스 댓글. 본문/Step 인용(2종 + NONE) · depth=1 답글 · 이모지 리액션(Slack 스타일) · 도움됨(모든 사용자 토글) ·
        soft delete · 편집됨 표시 · @멘션 · **GitHub 스타일 Diff 제안**(코드블럭 gutter 드래그 → 라인 범위 + after 코드).
        권한은 케이스 read 권한(`ErrorCaseAccess.requireRead`) 동일. Diff 제안의 상태 변경(반영/거부)은 *케이스 owner 만*.
        본문에 임베드된 스니펫/코드도 *본문의 일부* — 별도 CODE_BLOCK 인용 종류는 두지 않는다.
        "원문 보기" 는 FE 가 quoteSnapshot 으로 본문 내 첫 매치 위치를 찾아 slide.
    """
)
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/comments")
class CommentController(
    private val createCommentUseCase: CreateCommentUseCase,
    private val listCommentsUseCase: ListCommentsUseCase,
    private val updateCommentUseCase: UpdateCommentUseCase,
    private val deleteCommentUseCase: DeleteCommentUseCase,
    private val toggleHelpfulUseCase: ToggleHelpfulUseCase,
    private val addReactionUseCase: AddReactionUseCase,
    private val removeReactionUseCase: RemoveReactionUseCase,
    private val updateSuggestionStatusUseCase: UpdateSuggestionStatusUseCase,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort,
) {

    @Operation(
        summary = "댓글 작성",
        description = """
            인용 종류 (3종):
             - NONE: 인용 없음 — quoteSourceId/quoteSnapshot 무시
             - CASE_BODY: 본문 인용 — quoteSnapshot 필수 (본문에 임베드된 스니펫/코드도 본문 일부)
             - STEP: step 인용 — quoteSourceId = step id (같은 케이스 검증)

            **답글(parentCommentId 지정) 은 depth=1 강제** — 답글의 답글이면 서버가 top-level 로 redirect.
            quoteSnapshot 은 500자 초과 시 자동 잘림(응답에 `truncated=true`). 본문의 `@사용자` 는 멘션으로 적재.

            **Diff 제안 첨부**(선택) — `suggestion` 필드. 코드블럭의 라인 범위 + after 코드.
            첨부 시 자동 status=PENDING. sourceId 는 opaque(SNIPPET=markerId / MARKDOWN_CODE=FE가 부여한 식별자).
            제안 댓글은 보통 quote 와 함께 옴(oldCode == quoteSnapshot 권장 — FE 의 gutter 드래그 UX).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성됨"),
        ApiResponse(responseCode = "400", description = "검증 실패(인용 source 다른 케이스/존재 X 등)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Valid @RequestBody request: CreateCommentRequest,
    ): CommentResponse {
        val created = try {
            createCommentUseCase.invoke(
                CreateCommentCommand(
                    errorCaseId = caseId,
                    authorUserId = userId,
                    parentCommentId = request.parentCommentId,
                    body = request.body,
                    quoteSourceKind = request.quoteSourceKind,
                    quoteSourceId = request.quoteSourceId,
                    quoteSnapshot = request.quoteSnapshot,
                    suggestion = request.suggestion?.let {
                        CreateCommentCommand.SuggestionInput(
                            sourceType = it.sourceType,
                            sourceId = it.sourceId,
                            startLine = it.startLine,
                            endLine = it.endLine,
                            oldCode = it.oldCode,
                            newCode = it.newCode,
                        )
                    },
                )
            )
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: CommentInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }

        val caseOwnerUserId = errorCaseRepository.findById(caseId)?.ownerUserId ?: -1L
        val suggestion = commentRepository.findSuggestionByCommentId(created.id!!)
        // 단건 응답에선 reaction/helpful/mention 비어있는 상태(방금 만들었으니)
        return CommentResponse(
            id = created.id!!,
            errorCaseId = created.errorCaseId,
            authorUserId = created.authorUserId,
            isAuthorOfCase = created.authorUserId == caseOwnerUserId,
            parentCommentId = created.parentCommentId,
            body = created.body,
            quote = created.toQuoteDto(),
            reactions = emptyList(),
            helpful = CommentResponse.HelpfulDto(0, emptyList(), false),
            mentions = emptyList(),
            suggestion = suggestion?.toDto(),
            editedAt = created.editedAt,
            deletedAt = created.deletedAt,
            createdAt = created.createdAt,
            updatedAt = created.updatedAt,
            replies = emptyList(),
        )
    }

    @Operation(
        summary = "댓글 목록 (cursor + sort + filter + 트리)",
        description = """
            top-level + 답글(depth=1 children) 트리. cursor 페이징은 top-level 만(답글은 부모와 함께).
            - sort=NEWEST(기본) / OLDEST / HELPFUL
            - quoteKind=ALL(기본) / GENERAL / CASE_BODY / STEP
            - HELPFUL 정렬은 cursor 미지원(전체 반환).
            응답에 `quoteSummary` 가 포함되어 사이드 패널 카운트에 사용.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "정렬", example = "NEWEST") @RequestParam(required = false, defaultValue = "NEWEST") sort: String,
        @Parameter(description = "인용 종류 필터", example = "ALL") @RequestParam(required = false, defaultValue = "ALL") quoteKind: String,
        @Parameter(description = "다음 페이지 커서") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100)") @RequestParam(required = false, defaultValue = "20") size: Int,
    ): CommentListResponse {
        val sortEnum = runCatching { ListCommentsUseCase.Sort.valueOf(sort.uppercase()) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sort: $sort") }
        val filterEnum = runCatching { ListCommentsUseCase.QuoteKindFilter.valueOf(quoteKind.uppercase()) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid quoteKind: $quoteKind") }

        val r = try {
            listCommentsUseCase.invoke(
                ListCommentsUseCase.Input(
                    errorCaseId = caseId,
                    requesterUserId = userId,
                    sort = sortEnum,
                    quoteKindFilter = filterEnum,
                    cursor = cursor,
                    size = size,
                )
            )
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }

        val caseOwnerUserId = errorCaseRepository.findById(caseId)?.ownerUserId ?: -1L
        return CommentListResponse(
            items = r.items.map { it.toResponse(caseOwnerUserId) },
            nextCursor = r.nextCursor,
            hasNext = r.hasNext,
            quoteSummary = CommentListResponse.QuoteSummaryDto(
                general = r.quoteSummary.general,
                caseBody = r.quoteSummary.caseBody,
                steps = r.quoteSummary.steps.map { CommentListResponse.KeyCountDto(it.sourceId, it.count) },
            ),
        )
    }

    @Operation(summary = "댓글 수정", description = "작성자 본인만. body 만 변경. 응답에 (편집됨) 표시용 `editedAt` 세팅.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정됨"),
        ApiResponse(responseCode = "400", description = "검증 실패 / 삭제된 댓글", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작성자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글 없음", content = [Content()]),
    )
    @PatchMapping("/{commentId}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
        @Valid @RequestBody request: UpdateCommentRequest,
    ): CommentResponse {
        val c = try {
            updateCommentUseCase.invoke(UpdateCommentCommand(commentId, userId, request.body))
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CommentAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: CommentInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        val caseOwnerUserId = errorCaseRepository.findById(caseId)?.ownerUserId ?: -1L
        val suggestion = commentRepository.findSuggestionByCommentId(c.id!!)
        return CommentResponse(
            id = c.id!!,
            errorCaseId = c.errorCaseId,
            authorUserId = c.authorUserId,
            isAuthorOfCase = c.authorUserId == caseOwnerUserId,
            parentCommentId = c.parentCommentId,
            body = c.body,
            quote = c.toQuoteDto(),
            reactions = emptyList(),
            helpful = CommentResponse.HelpfulDto(0, emptyList(), false),
            mentions = emptyList(),
            suggestion = suggestion?.toDto(),
            editedAt = c.editedAt, deletedAt = c.deletedAt,
            createdAt = c.createdAt, updatedAt = c.updatedAt,
            replies = emptyList(),
        )
    }

    @Operation(summary = "댓글 삭제 (soft)", description = "작성자 본인만. soft delete — body 는 placeholder 로, deletedAt 세팅. 답글이 있는 부모도 같은 방식.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨(멱등)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작성자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글 없음", content = [Content()]),
    )
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
    ) {
        try {
            deleteCommentUseCase.invoke(commentId, userId)
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CommentAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }

    @Operation(
        summary = "도움됨 토글",
        description = "**모든 사용자가 토글 가능**(케이스 read 권한자). 이미 표시한 사람이 또 누르면 해제. 응답에 카운트 + 누른 사용자 userId 목록."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토글 결과"),
        ApiResponse(responseCode = "400", description = "삭제된 댓글", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글 없음", content = [Content()]),
    )
    @PostMapping("/{commentId}/helpful")
    fun toggleHelpful(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
    ): HelpfulToggleResponse {
        val r = try {
            toggleHelpfulUseCase.invoke(commentId, userId)
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: CommentInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return HelpfulToggleResponse(r.helpedByMe, r.count, r.userIds)
    }

    @Operation(summary = "이모지 리액션 추가", description = "Slack 스타일 — 같은 사용자가 같은 이모지를 또 누르면 멱등(no-op).")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "리액션 집계"),
        ApiResponse(responseCode = "400", description = "삭제된 댓글 / 빈 이모지", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글 없음", content = [Content()]),
    )
    @PostMapping("/{commentId}/reactions")
    fun addReaction(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
        @Valid @RequestBody request: AddReactionRequest,
    ): ReactionResponse {
        val r = try {
            addReactionUseCase.invoke(commentId, userId, request.emoji)
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: CommentInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return ReactionResponse(r.emoji, r.count, r.userIds, r.reactedByMe)
    }

    @Operation(
        summary = "Diff 제안 상태 변경",
        description = """
            댓글에 첨부된 GitHub 스타일 Diff 제안의 상태(PENDING/APPLIED/REJECTED) 토글.
            **권한: 케이스 owner 만** (PR author 와 동치 — *제안 수용 권한* 은 코드 소유자).
            댓글 작성자는 PENDING 으로 제안만 하고 상태를 직접 바꿀 수 없음.
            실제 코드 반영(snippet/step body 변경)은 작성자가 수동으로 진행 — Phase 1 에선 *상태 표시만*.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 변경됨"),
        ApiResponse(responseCode = "400", description = "댓글에 제안이 첨부돼있지 않음", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 owner 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글/케이스 없음", content = [Content()]),
    )
    @PatchMapping("/{commentId}/suggestion/status")
    fun updateSuggestionStatus(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
        @Valid @RequestBody request: UpdateSuggestionStatusRequest,
    ): SuggestionStatusResponse {
        val s = try {
            updateSuggestionStatusUseCase.invoke(
                UpdateSuggestionStatusCommand(commentId, userId, request.status)
            )
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CommentAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: CommentInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return SuggestionStatusResponse(commentId = s.commentId, status = s.status, updatedAt = s.updatedAt)
    }

    @Operation(summary = "이모지 리액션 해제", description = "본인 것만. 이미 없으면 멱등.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "해제 후 집계"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "댓글 없음", content = [Content()]),
    )
    @DeleteMapping("/{commentId}/reactions/{emoji}")
    fun removeReaction(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "댓글 ID") @PathVariable commentId: Long,
        @Parameter(description = "이모지") @PathVariable emoji: String,
    ): ReactionResponse {
        val r = try {
            removeReactionUseCase.invoke(commentId, userId, emoji)
        } catch (e: CommentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return ReactionResponse(r.emoji, r.count, r.userIds, r.reactedByMe)
    }
}
