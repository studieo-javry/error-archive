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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetMyRecentActivitiesUseCase
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ActivitySummaryResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActivitiesListResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.RecentActivityResponse

/**
 * 다른 사용자의 프로필 페이지 (author-profile.html) 용 endpoint.
 *
 * 자기 자신 endpoint (`/users/me/...`) 는 [MeController]. 여기는 남 profile 을 볼 때 —
 * viewer/target 관점 필터 (visibility + workspace membership) 가 반드시 적용된다.
 */
@Tag(
    name = "users-profile",
    description = "다른 사용자 프로필 페이지 endpoint. viewer 관점의 visibility 필터가 적용된다."
)
@RestController
@RequestMapping("/api/v1/users")
class PublicProfileController(
    private val getMyRecentActivitiesUseCase: GetMyRecentActivitiesUseCase,
) {

    @Operation(
        summary = "특정 사용자의 최근 활동 timeline (author-profile 용)",
        description = """
            author-profile.html 에서 노출. **viewer 관점 필터** 적용:

            - target 이 만든 PUBLIC 케이스 관련 activity → 항상 노출
            - target 이 만든 WORKSPACE 케이스 activity → viewer 가 그 workspace 멤버여야 노출
            - target 이 만든 PRIVATE 케이스 activity → 완전 제외

            자기 자신 profile 이면 (`targetUserId == viewer`) 자동으로 `/users/me/recent-activities` 와 동일한
            결과 반환 (self view). 하지만 self view 는 별도 endpoint 를 쓰는 게 정석.

            **Summary counts** 는 viewer view 에서 근사값 — window 전체 count 가 아니라
            "볼 수 있는 top-N" 기반 카운트. 정보 유출 방지 (실 총 활동량 노출 X).

            **정렬**: `occurredAt DESC`. 정렬 후 limit 컷.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "OK",
            content = [Content(schema = Schema(implementation = RecentActivitiesListResponse::class))]
        ),
    )
    @GetMapping("/{targetUserId}/recent-activities")
    fun recentActivitiesOf(
        @Parameter(description = "대상 사용자 id", example = "3")
        @PathVariable targetUserId: Long,
        @Parameter(hidden = true) @AuthenticationPrincipal viewerUserId: Long,
        @Parameter(description = "활동 기준 윈도우 (일). 기본 7.")
        @RequestParam(defaultValue = "7") windowDays: Int,
        @Parameter(description = "응답 항목 수. 1~100. 기본 30.")
        @RequestParam(defaultValue = "30") limit: Int,
    ): RecentActivitiesListResponse {
        val result = getMyRecentActivitiesUseCase.invoke(
            GetMyRecentActivitiesUseCase.Input(
                userId = targetUserId,
                viewerUserId = viewerUserId,
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
}
