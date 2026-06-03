package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.command.UpdateMyProfileCommand
import org.studieojavry.iamapi.auth.application.usecase.GetMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetPublicProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.ListMyConnectionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.RequestAccountDeletionUseCase
import org.studieojavry.iamapi.auth.application.usecase.RestoreAccountUseCase
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.UserNotFoundException
import org.studieojavry.iamapi.auth.infrastructure.security.AuthCookieFactory
import org.studieojavry.iamapi.auth.presentation.web.dto.request.UpdateMyProfileRequest
import org.studieojavry.iamapi.auth.presentation.web.dto.response.ConnectionResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.MyProfileResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.PublicProfileResponse

@Tag(name = "users-me", description = "내 프로필 조회/수정/회원탈퇴. 토큰의 sub(userId) 기준.")
@RestController
@RequestMapping("/api/v1/users")
class
UserController(
    private val getMyProfileUseCase: GetMyProfileUseCase,
    private val updateMyProfileUseCase: UpdateMyProfileUseCase,
    private val requestAccountDeletionUseCase: RequestAccountDeletionUseCase,
    private val restoreAccountUseCase: RestoreAccountUseCase,
    private val getPublicProfileUseCase: GetPublicProfileUseCase,
    private val listMyConnectionsUseCase: ListMyConnectionsUseCase,
    private val authCookieFactory: AuthCookieFactory,
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
            language = r.language,
            timezone = r.timezone,
            theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "내 프로필·환경설정 부분 수정",
        description = """
            null/미포함 필드는 변경 없음. `clearAvatar`/`clearBio`/`clearLanguage`/`clearTimezone`/`clearDefaultWorkspace` 로 명시 비우기.
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
                    clearAvatar = request.clearAvatar,
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
            language = r.language,
            timezone = r.timezone,
            theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "회원 탈퇴 (요청) — soft delete",
        description = """
            **본인만**. ACTIVE → `PENDING_DELETION` 으로 전환하고 **30일 grace period** 시작.

            **동시 처리** (단일 트랜잭션):
            - 워크스페이스 멤버십 전부 정리 (마지막 ADMIN 이면 다음 멤버 자동 ADMIN 승격, 유일 멤버면 워크스페이스 hard-delete).
            - 팔로우 양방향 엣지 전부 삭제.
            - refresh 토큰 전부 폐기 + 응답 `Set-Cookie` 로 refresh 쿠키 만료.

            **30일 안에 같은 OAuth 로 로그인** 하면 PENDING_DELETION 상태로 진입 → 클라가 `pendingDeletionAt` 를 보고
            "복구하기" 버튼 노출 → `POST /users/me/restore` 호출. 만료되면 `FinalizeDeletedAccountsUseCase` 가
            `DELETED` 로 확정 + PII 익명화 + OAuth identity 삭제(같은 GitHub 계정으로 재가입 가능).

            **core-api 의 owner/uploader user_id 는 그대로 유지**(ghost). 프런트가 `users/{id}` 조회 시 status=DELETED 면 "탈퇴한 사용자" 로 표시.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "204", description = "PENDING_DELETION 으로 전환됨(또는 이미 그 상태). 응답 헤더로 refresh 쿠키 만료."
        ),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(
            responseCode = "409", description = "ACTIVE 도 PENDING_DELETION 도 아닌 상태(예: SUSPENDED)",
            content = [Content(examples = [ExampleObject(value = """
                {"type":"about:blank","title":"Conflict","status":409,"detail":"only ACTIVE users can request deletion: current=SUSPENDED","instance":"/api/v1/users/me"}
            """)])]
        )
    )
    @DeleteMapping("/me")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
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
        description = """
            **본인만**. PENDING_DELETION → ACTIVE 로 복귀. grace period(기본 30일) 안에서만 가능.

            **주의**: 워크스페이스 멤버십·팔로우는 탈퇴 시점에 이미 삭제되어 **복원되지 않는다**. 사용자가 다시 가입·팔로우 필요.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "ACTIVE 로 복귀(또는 이미 ACTIVE)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "409", description = "PENDING_DELETION 도 ACTIVE 도 아닌 상태", content = [Content()])
    )
    @PostMapping("/me/restore")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreMe(@Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt) {
        val userId = currentUserId(jwt)
        try {
            restoreAccountUseCase.invoke(userId)
        } catch (e: UserNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
    }

    @Operation(
        summary = "공개 프로필 조회",
        description = """
            다른 사용자도 볼 수 있는 공개 프로필. 비공개 필드(email/preferences/탈퇴시각)는 노출 안 함.
            `status=DELETED` 면 displayName 이 익명화된 placeholder(`deleted_user_{id}`)로 반환되고 다른 표시 정보는 null.
            (Phase 1 단계: 공개 케이스 미리보기는 core-api 가 별도 제공.)
        """
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
        summary = "내 OAuth 연결 목록",
        description = "Settings → Account → 'Login & connections' 화면용. provider 내부 식별자는 노출 안 함."
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

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
