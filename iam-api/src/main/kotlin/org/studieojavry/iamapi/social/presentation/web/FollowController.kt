package org.studieojavry.iamapi.social.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.social.application.command.FollowUserCommand
import org.studieojavry.iamapi.social.application.command.UnfollowUserCommand
import org.studieojavry.iamapi.social.application.usecase.FollowUserUseCase
import org.studieojavry.iamapi.social.application.usecase.GetFollowStatusUseCase
import org.studieojavry.iamapi.social.application.usecase.GetFollowersUseCase
import org.studieojavry.iamapi.social.application.usecase.GetFollowingUseCase
import org.studieojavry.iamapi.social.application.usecase.UnfollowUserUseCase
import org.studieojavry.iamapi.social.presentation.web.dto.response.FollowActionResponse
import org.studieojavry.iamapi.social.presentation.web.dto.response.FollowListResponse
import org.studieojavry.iamapi.social.presentation.web.dto.response.FollowStatusResponse
import org.studieojavry.iamapi.social.presentation.web.dto.response.UserSummaryResponse

@Tag(
    name = "social-follow",
    description = "사용자 팔로우/언팔로우 + 공개 사회 그래프 조회. PUT/DELETE 는 인증 필요, GET 3개는 public."
)
@RestController
@RequestMapping("/api/v1/users")
class FollowController(
    private val followUserUseCase: FollowUserUseCase,
    private val unfollowUserUseCase: UnfollowUserUseCase,
    private val getFollowStatusUseCase: GetFollowStatusUseCase,
    private val getFollowersUseCase: GetFollowersUseCase,
    private val getFollowingUseCase: GetFollowingUseCase
) {

    @Operation(summary = "팔로우(멱등)", description = "이미 팔로우 중이면 `changed=false`. 자기 자신 팔로우는 400.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "자기 자신 팔로우 시도", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @PutMapping("/{userId}/follow")
    fun follow(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "팔로우할 사용자 ID") @PathVariable userId: Long
    ): FollowActionResponse {
        val me = currentUserId(jwt)
        if (me == userId) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot follow self")
        val result = followUserUseCase.invoke(FollowUserCommand(followerId = me, followeeId = userId))
        return FollowActionResponse(
            followerId = me,
            followeeId = userId,
            following = true,
            changed = result.created
        )
    }

    @Operation(summary = "언팔로우(멱등)", description = "이미 안 팔로우 중이면 `changed=false`.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @DeleteMapping("/{userId}/follow")
    fun unfollow(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "언팔로우할 사용자 ID") @PathVariable userId: Long
    ): FollowActionResponse {
        val me = currentUserId(jwt)
        val result = unfollowUserUseCase.invoke(UnfollowUserCommand(followerId = me, followeeId = userId))
        return FollowActionResponse(
            followerId = me,
            followeeId = userId,
            following = false,
            changed = result.removed
        )
    }

    @Operation(
        summary = "팔로우 상태 (public, viewer-aware)",
        description = "공개 카운트 + 인증된 viewer 가 있으면 양방향 관계/mutual/self 표시. 비인증도 호출 가능."
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @SecurityRequirements
    @GetMapping("/{userId}/follow-status")
    fun status(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt?,
        @Parameter(description = "대상 사용자 ID") @PathVariable userId: Long
    ): FollowStatusResponse {
        val viewerId = jwt?.subject?.toLongOrNull()
        val r = getFollowStatusUseCase.invoke(targetUserId = userId, viewerUserId = viewerId)
        return FollowStatusResponse(
            userId = r.userId,
            followersCount = r.followersCount,
            followingCount = r.followingCount,
            viewerFollowsTarget = r.viewerFollowsTarget,
            targetFollowsViewer = r.targetFollowsViewer,
            isMutual = r.isMutual,
            isSelf = r.isSelf
        )
    }

    @Operation(summary = "팔로워 목록 (public, offset 페이징)", description = "그 사용자를 팔로우하는 사람들.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @SecurityRequirements
    @GetMapping("/{userId}/followers")
    fun followers(
        @Parameter(description = "대상 사용자 ID") @PathVariable userId: Long,
        @Parameter(description = "0-base 페이지", example = "0") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") size: Int
    ): FollowListResponse {
        val r = getFollowersUseCase.invoke(userId, page, size)
        return FollowListResponse(
            items = r.items.map { UserSummaryResponse(it.userId, it.handle, it.displayName, it.avatarUrl) },
            page = r.page,
            size = r.size,
            totalElements = r.totalElements,
            totalPages = r.totalPages
        )
    }

    @Operation(summary = "팔로잉 목록 (public, offset 페이징)", description = "그 사용자가 팔로우하는 사람들.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @SecurityRequirements
    @GetMapping("/{userId}/following")
    fun following(
        @Parameter(description = "대상 사용자 ID") @PathVariable userId: Long,
        @Parameter(description = "0-base 페이지", example = "0") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") size: Int
    ): FollowListResponse {
        val r = getFollowingUseCase.invoke(userId, page, size)
        return FollowListResponse(
            items = r.items.map { UserSummaryResponse(it.userId, it.handle, it.displayName, it.avatarUrl) },
            page = r.page,
            size = r.size,
            totalElements = r.totalElements,
            totalPages = r.totalPages
        )
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
