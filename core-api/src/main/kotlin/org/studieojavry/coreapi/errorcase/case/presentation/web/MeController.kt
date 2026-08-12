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
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyFollowingFeedUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActiveCasesUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActivitiesUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistFeedUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyWatchlistUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetSuggestedFolloweesUseCase
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ActivitySummaryResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorCaseSummaryResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.FollowingFeedResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.SuggestedFolloweesResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActiveCaseResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActiveCasesListResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActivitiesListResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActivityResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistFeedItemResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistFeedResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistItemResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.WatchlistListResponse
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus

@Tag(
    name = "users-me",
    description = "현재 사용자(`/users/me/*`) 대시보드 endpoint. 홈 화면용 최근 활동/케이스 요약."
)
@RestController
@RequestMapping("/api/v1/users/me")
class MeController(
    private val getMyRecentActiveCasesUseCase: GetMyRecentActiveCasesUseCase,
    private val getMyRecentActivitiesUseCase: GetMyRecentActivitiesUseCase,
    private val getMyWatchlistUseCase: GetMyWatchlistUseCase,
    private val getMyWatchlistFeedUseCase: GetMyWatchlistFeedUseCase,
    private val getMyFollowingFeedUseCase: GetMyFollowingFeedUseCase,
    private val getSuggestedFolloweesUseCase: GetSuggestedFolloweesUseCase,
) {

    @Operation(
        summary = "내 최근 활동 케이스 목록",
        description = """
            홈 대시보드 카드용. **내가 만든** 케이스 중 *최근 N일* 안에 활동이 있는 것.

            **활동 정의** (4 sources max)
            - 케이스 자체 변경 (제목/본문/status)
            - 누군가의 댓글 (deleted 제외)
            - 누군가의 step 추가
            - 누군가의 solution 등록

            **정렬**: `lastActivityAt DESC` (가장 최근 활동 케이스 먼저).
            **MVP 한계**: 내 최근 updatedAt 50건 안에서만 활동을 본다 — 50건 밖에서 댓글만
            일어난 매우 오래된 케이스는 드물지만 누락될 수 있다.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = RecentActiveCasesListResponse::class))]
        ),
    )
    @GetMapping("/recent-active-cases")
    fun recentActiveCases(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "활동 기준 윈도우 (일). 기본 7.")
        @RequestParam(defaultValue = "7") windowDays: Int,
        @Parameter(description = "응답 카드 수. 1~20. 기본 5.")
        @RequestParam(defaultValue = "5") limit: Int,
    ): RecentActiveCasesListResponse {
        val result = getMyRecentActiveCasesUseCase.invoke(
            GetMyRecentActiveCasesUseCase.Input(
                userId = userId,
                windowDays = windowDays,
                limit = limit,
            )
        )
        return RecentActiveCasesListResponse(items = result.items.map { RecentActiveCaseResponse.from(it) })
    }

    @Operation(
        summary = "내 최근 활동 timeline",
        description = """
            홈 대시보드 timeline 용. **내가** 최근 N일 안에 한 활동.

            **type 종류**
            - `CASE_CREATED` — 케이스를 만들었음
            - `COMMENT_POSTED` — 댓글을 작성 (deleted 제외)
            - `STEP_ADDED` — step 을 추가
            - `SOLUTION_REGISTERED` — solution 을 등록

            **MVP 제외**: `CASE_RESOLVED` — case status 변경 history 미보관으로 정확한 시각
            추적 불가. 후속에서 status history 도입 시 추가.

            **정렬**: `occurredAt DESC`. 같은 케이스에서 여러 활동이면 각각 별도 row.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = RecentActivitiesListResponse::class))]
        ),
    )
    @GetMapping("/recent-activities")
    fun recentActivities(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "활동 기준 윈도우 (일). 기본 7.")
        @RequestParam(defaultValue = "7") windowDays: Int,
        @Parameter(description = "응답 항목 수. 1~100. 기본 30.")
        @RequestParam(defaultValue = "30") limit: Int,
    ): RecentActivitiesListResponse {
        val result = getMyRecentActivitiesUseCase.invoke(
            GetMyRecentActivitiesUseCase.Input(
                userId = userId,
                viewerUserId = userId, // self view
                windowDays = windowDays,
                limit = limit,
            )
        )
        return RecentActivitiesListResponse(
            items = result.items.map { RecentActivityResponse.from(it) },
            windowDays = result.windowDays,
            summary = ActivitySummaryResponse(
                byType = result.summary.byType.mapKeys { it.key.name },
            ),
        )
    }

    @Operation(
        summary = "내 watchlist 관리 목록 (Library > Watchlist 페이지)",
        description = """
            **관리용** 목록 — 홈 위젯 (`/watchlist-feed`) 과 다름:
            - 홈 위젯: 활동 감지 · 상위 20 · latest actor
            - 이 endpoint: 컬렉션 관리 · sort/search/cursor · watchlist-level metadata

            **정렬** (`sort`):
            - `lastActivity` (default): 활동 최근순 · (unread > 0 desc, lastActivityAt desc)
            - `added`: 담은 시각 최근순
            - `title`: title asc (case-insensitive)

            **검색** (`search`): title 또는 tag 부분 매치 (case-insensitive).

            **Cursor**: `nextCursor` 값을 다음 요청 `cursor` 로 그대로 전달. null 이면 마지막 페이지.

            **응답 필드**: case-level (visibility/tags/exceptionClass 등) + watchlist-level (addedAt/lastActivityAt/unreadCount/isOwn).
            `isOwn` = case owner==me (MINE 배지용 · 자동 포함이라 UI 제거 불가 힌트).
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = WatchlistListResponse::class))]
        ),
    )
    @GetMapping("/watchlist")
    fun myWatchlist(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "정렬 · `lastActivity` (default) / `added` / `title`.")
        @RequestParam(defaultValue = "lastActivity") sort: String,
        @Parameter(description = "검색 키워드 — title 또는 tag 부분 매치. `search` 는 deprecated 별칭.")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "[deprecated] `q` 사용. 하위호환 유지.", deprecated = true)
        @RequestParam(required = false) search: String?,
        @Parameter(description = "status 필터(다중값 comma) — OPEN,IN_PROGRESS,RESOLVED. 잘못된 값 400.")
        @RequestParam(required = false) status: String?,
        @Parameter(description = "true 면 안 읽은 업데이트 있는 것만(unreadCount>0).")
        @RequestParam(defaultValue = "false") unread: Boolean,
        @Parameter(description = "이전 응답의 `nextCursor` 그대로 전달.")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "응답 항목 수. 1~100. 기본 30.")
        @RequestParam(defaultValue = "30") limit: Int,
    ): WatchlistListResponse {
        // 잘못된 status → IllegalArgumentException → GlobalExceptionHandler 가 400 으로 매핑.
        val statusSet: Set<ErrorCaseStatus>? = status?.split(",")
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.map { raw ->
                val u = raw.uppercase()
                ErrorCaseStatus.entries.firstOrNull { it.name == u }
                    ?: throw IllegalArgumentException("invalid status: $raw")
            }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val result = getMyWatchlistUseCase.invoke(
            GetMyWatchlistUseCase.Input(
                userId = userId,
                sort = GetMyWatchlistUseCase.Sort.fromCode(sort),
                search = q ?: search,   // q 우선, 없으면 search(deprecated) 하위호환
                status = statusSet,
                unreadOnly = unread,
                cursor = cursor,
                limit = limit,
            )
        )
        return WatchlistListResponse(
            items = result.items.map { WatchlistItemResponse.from(it, viewerUserId = userId) },
            nextCursor = result.nextCursor,
            hasNext = result.hasNext,
            totalCount = result.totalCount,
            statusCounts = result.statusCounts.mapKeys { it.key.name },
        )
    }

    @Operation(
        summary = "내 watchlist feed (홈 'Watchlist · 업데이트' 섹션)",
        description = """
            *watchlist 한* 케이스들의 최근 활동 카드.

            **각 카드 필드**: case 메타 + `latestActivityType` (CASE_UPDATED / CASE_RESOLVED /
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
        return WatchlistFeedResponse(items = result.items.map { WatchlistFeedItemResponse.from(it, result.authors) })
    }

    @Operation(
        summary = "내 following feed (홈 'Following · 새 케이스' 섹션)",
        description = """
            내가 팔로우한 사용자들의 최근 *PUBLIC* 케이스. `ErrorCaseSummaryResponse` 와 동일 schema —
            작성자 hydration 포함 (handle/displayName/avatar/bio/isFollowing).

            **정렬**: `createdAt DESC, id DESC`. **권한**: 인증 필수.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = FollowingFeedResponse::class))]
        ),
    )
    @GetMapping("/following-feed")
    fun followingFeed(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "응답 케이스 수. 1~100. 기본 20.")
        @RequestParam(defaultValue = "20") limit: Int,
    ): FollowingFeedResponse {
        val result = getMyFollowingFeedUseCase.invoke(
            GetMyFollowingFeedUseCase.Input(userId = userId, limit = limit)
        )
        return FollowingFeedResponse.from(result)
    }

    @Operation(
        summary = "추천 사용자 (Follow 추천 — 홈의 Following empty state)",
        description = """
            팔로우 안 한 *다른 사용자* 추천. 신호:
            - **M** (me-too overlap) weight 5
            - **L** (watchlist overlap) weight 4
            - **W** (workspace co-member) weight 3

            본인 / 이미 follow / inactive 제외. score desc 정렬 → top N.
            결과 < limit 시 **top contributor** (최근 30d PUBLIC case 작성자) 로 fallback.
            **캐싱**: 15분 TTL, userId+limit 키.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = SuggestedFolloweesResponse::class))]
        ),
    )
    @GetMapping("/suggested-followees")
    fun suggestedFollowees(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "응답 사용자 수. 1~20. 기본 3.")
        @RequestParam(defaultValue = "3") limit: Int,
    ): SuggestedFolloweesResponse {
        val result = getSuggestedFolloweesUseCase.invoke(
            GetSuggestedFolloweesUseCase.Input(userId = userId, limit = limit)
        )
        return SuggestedFolloweesResponse.from(result)
    }
}
