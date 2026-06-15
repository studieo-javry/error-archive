package org.studieojavry.coreapi.errorcase.case.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistFeedUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistUseCase
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorCaseSummaryResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistFeedItemResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistFeedResponse

@Tag(
    name = "users-me",
    description = "현재 사용자(`/users/me/*`) 대시보드 endpoint. 홈 화면용 watchlist / watchlist-feed."
)
@RestController
@RequestMapping("/api/v1/users/me")
class MeController(
    private val getMyWatchlistUseCase: GetMyWatchlistUseCase,
    private val getMyWatchlistFeedUseCase: GetMyWatchlistFeedUseCase,
) {

    @Operation(
        summary = "내 watchlist 목록",
        description = "사용자가 명시적으로 follow 한 case 들 (즐겨찾기). createdAt DESC. limit 1~100."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "OK"),
    )
    @GetMapping("/watchlist")
    fun myWatchlist(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "응답 항목 수. 1~100. 기본 30.")
        @RequestParam(defaultValue = "30") limit: Int,
    ): List<ErrorCaseSummaryResponse> {
        return getMyWatchlistUseCase.invoke(userId, limit).map { ErrorCaseSummaryResponse.from(it) }
    }

    @Operation(
        summary = "내 watchlist feed (홈 'Watchlist · 업데이트' 섹션)",
        description = """
            *watchlist 한* 케이스들의 최근 활동 카드.

            **각 카드 필드**: case 메타 + `latestActivitySource` (CASE_RESOLVED /
            COMMENT_POSTED / STEP_ADDED / SOLUTION_REGISTERED) + `latestActivityActorUserId` + `lastActivityAt`
            + `unreadActivityCount` (case 상세 GET 이후 다른 사람 활동 수).

            **정렬**: `(unreadActivityCount > 0 desc, lastActivityAt desc)` — 안 본 업데이트 있는 카드 상단.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = WatchlistFeedResponse::class))]
        ),
    )
    @GetMapping("/watchlist-feed")
    fun watchlistFeed(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "응답 카드 수. 1~100. 기본 20.")
        @RequestParam(defaultValue = "20") limit: Int,
    ): WatchlistFeedResponse {
        val result = getMyWatchlistFeedUseCase.invoke(
            GetMyWatchlistFeedUseCase.Input(userId = userId, limit = limit)
        )
        return WatchlistFeedResponse(items = result.items.map { WatchlistFeedItemResponse.from(it) })
    }
}
