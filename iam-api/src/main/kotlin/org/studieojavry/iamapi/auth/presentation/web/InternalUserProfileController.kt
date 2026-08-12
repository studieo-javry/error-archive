package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.auth.application.usecase.GetUserProfilesUseCase

/**
 * **Internal-only** — 검색/목록 응답에 작성자 프로필을 enrichment 하기 위한 batch endpoint.
 *
 * 호출자(core-api 등) 가 결과 항목의 ownerUserId 들을 모아 한 번에 보내면
 * `avatarUrl / displayName / bio / isFollowing(viewer 기준)` 까지 한 응답으로 받아옴 — N+1 회피.
 *
 * gateway 라우팅 X. internal JWT(aud=iam-api) 검증.
 */
@Tag(name = "users-internal", description = "service-to-service: 사용자 프로필 batch 조회.")
@RestController
@RequestMapping("/internal/users")
class InternalUserProfileController(
    private val getUserProfiles: GetUserProfilesUseCase,
) {

    @Operation(
        summary = "여러 userId 의 프로필 batch 조회",
        description = "검색/목록 응답에 작성자 칩/popover 표시용. viewerUserId 가 null 이면 isFollowing 은 모두 false."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "userIds 비어 있거나 초과", content = [Content()]),
        ApiResponse(responseCode = "401", description = "internal-auth 실패", content = [Content()]),
    )
    @PostMapping("/profiles")
    fun batchProfiles(
        @Valid @RequestBody req: BatchProfileRequest,
    ): BatchProfileResponse {
        val items = getUserProfiles.invoke(req.userIds, req.viewerUserId)
        return BatchProfileResponse(
            items = items.map {
                UserProfileResponse(
                    userId = it.userId,
                    handle = it.handle,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl,
                    bio = it.bio,
                    isFollowing = it.isFollowing,
                )
            }
        )
    }

    data class BatchProfileRequest(
        @field:NotEmpty
        @field:Size(max = 200)
        val userIds: List<Long>,
        val viewerUserId: Long?,
    )

    data class BatchProfileResponse(
        val items: List<UserProfileResponse>,
    )

    data class UserProfileResponse(
        val userId: Long,
        val handle: String,
        val displayName: String,
        val avatarUrl: String?,
        val bio: String?,
        val isFollowing: Boolean,
    )
}
