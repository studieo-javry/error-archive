package org.studieojavry.coreapi.errorcase.comment.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.comment.application.usecase.MentionCandidatesUseCase

@Tag(name = "mention-candidates", description = "@멘션 자동완성 후보 — v2: 컨텍스트 + 권한 + 랭킹.")
@RestController
@RequestMapping("/api/v1/error-cases/{caseId}/mention-candidates")
class MentionCandidatesController(
    private val mentionCandidatesUseCase: MentionCandidatesUseCase,
) {

    @Operation(
        summary = "멘션 자동완성 후보",
        description = """
            **순서**(랭킹): 케이스 댓글 참여자(30) > 케이스 owner(20) > 워크스페이스 멤버(15) > iam-api 검색(5) — *prefix 일치 시 +12 보너스*.

            **권한 가드**:
            - 케이스 read 권한 없으면 403
            - viewer 본인은 결과에서 제외
            - 워크스페이스 케이스 → *멤버 외* 후보 자동 제거 (외부 사용자 노출 방지)

            **`badge`**: 후보의 *어느 source* 에서 왔는지 — `in-discussion` / `owner` / `workspace-member` / `search`.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping
    fun candidates(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable caseId: Long,
        @Parameter(description = "displayName prefix. 빈 값=default suggestion.", example = "ji")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "1..20", example = "10")
        @RequestParam(required = false, defaultValue = "10") limit: Int,
    ): List<MentionCandidateResponse> {
        val items = try {
            mentionCandidatesUseCase.invoke(caseId, userId, q, limit)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return items.map { MentionCandidateResponse(it.userId, it.displayName, it.handle, it.avatarUrl, it.badge) }
    }
}

@Schema(description = "멘션 자동완성 후보 1건.")
data class MentionCandidateResponse(
    val userId: Long,
    val displayName: String,
    @field:Schema(description = "@핸들. 동명이인 구분·표시용. 조회 실패 시 null.", example = "jihoo")
    val handle: String?,
    val avatarUrl: String?,
    @field:Schema(description = "후보 source — in-discussion · owner · workspace-member · search", example = "in-discussion")
    val badge: String?,
)
