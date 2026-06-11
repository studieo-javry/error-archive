package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.multipart.MultipartFile
import org.studieojavry.iamapi.auth.application.command.UpdateMyProfileCommand
import org.studieojavry.iamapi.auth.application.usecase.AvatarUploadInvalidException
import org.studieojavry.iamapi.auth.application.usecase.ConnectionNotFoundException
import org.studieojavry.iamapi.auth.application.usecase.DisconnectConnectionUseCase
import org.studieojavry.iamapi.auth.application.usecase.LastConnectionRemainingException
import org.studieojavry.iamapi.auth.application.usecase.ListMyConnectionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetPublicProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.ListMySessionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.RequestAccountDeletionUseCase
import org.studieojavry.iamapi.auth.application.usecase.RestoreAccountUseCase
import org.studieojavry.iamapi.auth.application.usecase.RevokeAllMySessionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.RevokeMySessionUseCase
import org.studieojavry.iamapi.auth.application.usecase.SearchUsersUseCase
import org.studieojavry.iamapi.auth.application.usecase.SessionNotFoundException
import org.studieojavry.iamapi.auth.application.usecase.UserNotFoundException
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyAvatarUseCase
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyProfileUseCase
import org.studieojavry.iamapi.auth.presentation.web.dto.response.ConnectionResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.SessionResponse
import java.util.UUID
import org.studieojavry.iamapi.auth.presentation.web.dto.request.UpdateMyProfileRequest
import org.studieojavry.iamapi.auth.presentation.web.dto.response.MyProfileResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.PublicProfileResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.UserSearchResponse

@Tag(name = "users-me", description = "내 프로필 조회/수정 + 다른 사용자 공개 프로필. 토큰의 sub(userId) 기준.")
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val getMyProfileUseCase: GetMyProfileUseCase,
    private val updateMyProfileUseCase: UpdateMyProfileUseCase,
    private val getPublicProfileUseCase: GetPublicProfileUseCase,
    private val searchUsersUseCase: SearchUsersUseCase,
    private val updateMyAvatarUseCase: UpdateMyAvatarUseCase,
    private val listMySessionsUseCase: ListMySessionsUseCase,
    private val revokeMySessionUseCase: RevokeMySessionUseCase,
    private val revokeAllMySessionsUseCase: RevokeAllMySessionsUseCase,
    private val requestAccountDeletionUseCase: RequestAccountDeletionUseCase,
    private val restoreAccountUseCase: RestoreAccountUseCase,
    private val authCookieFactory: org.studieojavry.iamapi.auth.infrastructure.security.AuthCookieFactory,
    private val listMyConnectionsUseCase: ListMyConnectionsUseCase,
    private val disconnectConnectionUseCase: DisconnectConnectionUseCase,
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
        summary = "사용자 검색 (displayName prefix)",
        description = "displayName prefix 로 사용자 검색. 대소문자 무시. q 가 비어있으면 최근 가입 순. 본인은 결과에서 제외."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/search")
    fun searchUsers(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "displayName prefix (대소문자 무시). 빈 값=최근 가입 순.", example = "ji")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "1..20", example = "10")
        @RequestParam(required = false, defaultValue = "10") limit: Int,
    ): List<UserSearchResponse> {
        val viewerId = currentUserId(jwt)
        return searchUsersUseCase.invoke(q, limit, viewerId).map {
            UserSearchResponse(userId = it.userId, displayName = it.displayName, avatarUrl = it.avatarUrl)
        }
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

    @Operation(
        summary = "내 아바타 업로드 (multipart)",
        description = "Content-Type: image/png|jpeg|webp|gif, 크기 ≤ 5MB. 갱신된 전체 프로필 반환. 기존 storage 파일은 함께 삭제."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "업로드됨 + 프로필 갱신"),
        ApiResponse(responseCode = "400", description = "허용 외 MIME / 크기 초과 / 빈 파일", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/me/avatar", consumes = ["multipart/form-data"])
    fun uploadAvatar(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @RequestParam("file") file: MultipartFile,
    ): MyProfileResponse {
        val userId = currentUserId(jwt)
        try {
            updateMyAvatarUseCase.upload(
                userId = userId,
                originalFileName = file.originalFilename,
                contentType = file.contentType,
                bytes = file.bytes,
            )
        } catch (e: AvatarUploadInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        val r = getMyProfileUseCase.invoke(userId)
        return MyProfileResponse(
            userId = r.userId, email = r.email, displayName = r.displayName,
            avatarUrl = r.avatarUrl, bio = r.bio, status = r.status,
            pendingDeletionAt = r.pendingDeletionAt, createdAt = r.createdAt,
            language = r.language, timezone = r.timezone, theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "내 아바타 제거",
        description = "user.avatarUrl 을 null 로. 기존 storage 파일도 함께 삭제. 멱등."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "제거됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @DeleteMapping("/me/avatar")
    fun removeAvatar(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
    ): MyProfileResponse {
        val userId = currentUserId(jwt)
        updateMyAvatarUseCase.remove(userId)
        val r = getMyProfileUseCase.invoke(userId)
        return MyProfileResponse(
            userId = r.userId, email = r.email, displayName = r.displayName,
            avatarUrl = r.avatarUrl, bio = r.bio, status = r.status,
            pendingDeletionAt = r.pendingDeletionAt, createdAt = r.createdAt,
            language = r.language, timezone = r.timezone, theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "회원 탈퇴 (요청) — soft delete",
        description = "ACTIVE → PENDING_DELETION. 30일 grace period 시작. refresh 쿠키 만료. 30일 안에 복구 가능 (POST /me/restore)."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "PENDING_DELETION 으로 전환됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "409", description = "ACTIVE 가 아닌 상태", content = [Content()])
    )
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(hidden = true) response: HttpServletResponse,
    ) {
        val userId = currentUserId(jwt)
        try {
            requestAccountDeletionUseCase.invoke(userId)
        } catch (e: UserNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString())
    }

    @Operation(
        summary = "탈퇴 취소 (복구)",
        description = "PENDING_DELETION → ACTIVE. grace 안에서만 가능. 워크스페이스 멤버십 / 팔로우는 복원 안 됨."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "복구됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "409", description = "PENDING_DELETION 이 아니거나 grace 만료", content = [Content()])
    )
    @PostMapping("/me/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreMe(@Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt) {
        val userId = currentUserId(jwt)
        try {
            restoreAccountUseCase.invoke(userId)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
    }

    @Operation(
        summary = "내 세션 목록",
        description = "활성 refresh family 목록. 각 항목은 디바이스 메타(deviceLabel/UA/IP) + 마지막 사용 시각."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/me/sessions")
    fun mySessions(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
    ): List<SessionResponse> {
        val userId = currentUserId(jwt)
        return listMySessionsUseCase.invoke(userId).map {
            SessionResponse(
                sessionId = it.sessionId,
                deviceLabel = it.deviceLabel,
                userAgent = it.userAgent,
                ipAddress = it.ipAddress,
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
                expiresAt = it.expiresAt,
                rememberMe = it.rememberMe,
            )
        }
    }

    @Operation(
        summary = "특정 세션 종료",
        description = "지정한 sessionId(=family) 의 모든 refresh row 를 revoke. 본인 소유만. 모르는 sessionId 는 404."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "종료됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "본인 활성 세션 중 해당 sessionId 없음", content = [Content()]),
    )
    @DeleteMapping("/me/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeSession(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "세션 ID (family UUID)") @PathVariable sessionId: UUID,
    ) {
        val userId = currentUserId(jwt)
        try {
            revokeMySessionUseCase.invoke(userId, sessionId)
        } catch (e: SessionNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        }
    }

    @Operation(
        summary = "모든 세션 종료 (Sign out everywhere)",
        description = "현재 디바이스 포함 모든 refresh family 를 revoke. 호출자 자신도 access 만료 후 강제 재로그인."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "전부 종료됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/me/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeAllSessions(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
    ) {
        val userId = currentUserId(jwt)
        revokeAllMySessionsUseCase.invoke(userId)
    }

    @Operation(
        summary = "내 OAuth 연결 목록",
        description = "Settings → Account → 'Login & connections' 용. provider 내부 식별자는 노출 안 함."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @GetMapping("/me/connections")
    fun myConnections(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt
    ): List<ConnectionResponse> {
        val userId = currentUserId(jwt)
        return listMyConnectionsUseCase.invoke(userId).map {
            ConnectionResponse(
                provider = it.provider,
                providerEmail = it.providerEmail,
                profileUrl = it.profileUrl,
                linkedAt = it.linkedAt,
            )
        }
    }

    @Operation(
        summary = "내 OAuth 연결 해제 (Disconnect)",
        description = "본인만. 마지막 남은 로그인 수단이면 409 (계정 락아웃 방지). 없는 provider 는 404."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "해당 provider 없음", content = [Content()]),
        ApiResponse(responseCode = "409", description = "마지막 남은 로그인 수단 — 해제 불가", content = [Content()]),
    )
    @DeleteMapping("/me/connections/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnect(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "provider name (e.g. github)") @PathVariable provider: String,
    ) {
        val userId = currentUserId(jwt)
        try {
            disconnectConnectionUseCase.invoke(userId, provider)
        } catch (e: ConnectionNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: LastConnectionRemainingException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "jwt.sub is not a valid userId")
}
