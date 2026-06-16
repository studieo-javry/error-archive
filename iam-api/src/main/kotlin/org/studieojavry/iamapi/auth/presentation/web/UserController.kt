package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.command.UpdateMyProfileCommand
import org.studieojavry.iamapi.auth.application.usecase.GetMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetPublicProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyProfileUseCase
import org.studieojavry.iamapi.auth.presentation.web.dto.request.UpdateMyProfileRequest
import org.studieojavry.iamapi.auth.presentation.web.dto.response.MyProfileResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.PublicProfileResponse

@Tag(name = "users-me", description = "내 프로필 조회/수정 + 다른 사용자 공개 프로필. 토큰의 sub(userId) 기준.")
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val getMyProfileUseCase: GetMyProfileUseCase,
    private val updateMyProfileUseCase: UpdateMyProfileUseCase,
    private val getPublicProfileUseCase: GetPublicProfileUseCase,
) {

    @Operation(
        summary = "내 프로필 조회",
        description = "Authorization 헤더의 JWT sub 클레임을 userId 로 해석해 본인 프로필을 반환. `status=PENDING_DELETION` 이면 `pendingDeletionAt` 으로 grace 시작 시각도 함께."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "토큰 없음/위조/만료", content = [Content()])
    )
    @GetMapping("/me")
    fun me(@Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt): MyProfileResponse {
        val userId = currentUserId(jwt)
        val r = getMyProfileUseCase.invoke(userId)
        return MyProfileResponse(
            userId = r.userId,
            email = r.email,
            displayName = r.displayName,
            avatarUrl = r.avatarUrl,
            bio = r.bio,
            status = r.status,
            pendingDeletionAt = r.pendingDeletionAt,
            createdAt = r.createdAt,
            language = r.language,
            timezone = r.timezone,
            theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "내 프로필·환경설정 부분 수정",
        description = """
            null/미포함 필드는 변경 없음. `clearBio`/`clearLanguage`/`clearTimezone`/`clearDefaultWorkspace` 로 명시 비우기.
            `theme` 은 enum 이라 항상 한 값(LIGHT/DARK/SYSTEM).
            `language` 는 BCP 47, `timezone` 은 IANA TZ id(잘못된 값 400).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 결과(전체 프로필)"),
        ApiResponse(responseCode = "400", description = "검증 실패(language/timezone 형식 불일치 등)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @PatchMapping("/me")
    fun updateMe(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: UpdateMyProfileRequest
    ): MyProfileResponse {
        val userId = currentUserId(jwt)
        val r = try {
            updateMyProfileUseCase.invoke(
                UpdateMyProfileCommand(
                    userId = userId,
                    displayName = request.displayName,
                    avatarUrl = request.avatarUrl,
                    bio = request.bio,
                    clearBio = request.clearBio,
                    language = request.language,
                    clearLanguage = request.clearLanguage,
                    timezone = request.timezone,
                    clearTimezone = request.clearTimezone,
                    theme = request.theme,
                    defaultWorkspaceId = request.defaultWorkspaceId,
                    clearDefaultWorkspace = request.clearDefaultWorkspace,
                )
            )
        } catch (e: IllegalArgumentException) {
            // 도메인 validate* 의 require 위반 (BCP 47 / IANA tz 형식 등)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return MyProfileResponse(
            userId = r.userId,
            email = r.email,
            displayName = r.displayName,
            avatarUrl = r.avatarUrl,
            bio = r.bio,
            status = r.status,
            pendingDeletionAt = r.pendingDeletionAt,
            createdAt = r.createdAt,
            language = r.language,
            timezone = r.timezone,
            theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "다른 사용자 공개 프로필 조회",
        description = "타 사용자의 *공개 가능 정보만* (email / preferences / pendingDeletionAt 등 비공개 정보 제외). `status=DELETED` 면 `isDeleted=true` 로 표시."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "사용자 없음", content = [Content()])
    )
    @GetMapping("/{userId}")
    fun publicProfile(
        @Parameter(description = "사용자 ID") @PathVariable userId: Long
    ): PublicProfileResponse {
        val r = try {
            getPublicProfileUseCase.invoke(userId)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        }
        return PublicProfileResponse(
            userId = r.userId,
            displayName = r.displayName,
            avatarUrl = r.avatarUrl,
            bio = r.bio,
            status = r.status,
            isDeleted = r.isDeleted,
        )
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "jwt.sub is not a valid userId")
}
