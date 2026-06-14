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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.command.UpdateMyProfileCommand
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.studieojavry.iamapi.auth.application.usecase.AvatarUploadInvalidException
import org.studieojavry.iamapi.auth.application.usecase.ConnectionNotFoundException
import org.studieojavry.iamapi.auth.application.usecase.DisconnectConnectionUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.GetPublicProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.LastConnectionRemainingException
import org.studieojavry.iamapi.auth.application.usecase.ListMyConnectionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.ListMySessionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.RevokeAllMySessionsUseCase
import org.studieojavry.iamapi.auth.application.usecase.RevokeMySessionUseCase
import org.studieojavry.iamapi.auth.application.usecase.SessionNotFoundException
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyAvatarUseCase
import org.studieojavry.iamapi.auth.presentation.web.dto.response.SessionResponse
import java.util.UUID
import org.studieojavry.iamapi.auth.application.usecase.RequestAccountDeletionUseCase
import org.studieojavry.iamapi.auth.application.usecase.RestoreAccountUseCase
import org.studieojavry.iamapi.auth.application.usecase.SearchUsersUseCase
import org.studieojavry.iamapi.auth.application.usecase.UpdateMyProfileUseCase
import org.studieojavry.iamapi.auth.application.usecase.UserNotFoundException
import org.studieojavry.iamapi.auth.presentation.web.dto.response.UserSearchResponse
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
    private val disconnectConnectionUseCase: DisconnectConnectionUseCase,
    private val updateMyAvatarUseCase: UpdateMyAvatarUseCase,
    private val searchUsersUseCase: SearchUsersUseCase,
    private val listMySessionsUseCase: ListMySessionsUseCase,
    private val revokeMySessionUseCase: RevokeMySessionUseCase,
    private val revokeAllMySessionsUseCase: RevokeAllMySessionsUseCase,
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
            handle = r.handle,
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
            **avatar 비우기는 `DELETE /users/me/avatar`** — 파일 자원이라 storage 정리까지 한 endpoint 에 묶음.
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
            handle = r.handle,
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
        summary = "사용자 검색 (displayName prefix)",
        description = """
            멘션 자동완성 등에 사용. **ACTIVE 사용자만** 반환, viewer 본인은 제외.

            - `q` 가 비어 있으면 *최근 가입한 ACTIVE* 사용자 순으로 (default suggestion 후보).
            - 권한/콘텐츠 가시성 가드는 *호출자 책임* — 본 endpoint 는 공개 사용자 디렉토리.
              비공개 콘텐츠 멘션 자동완성은 core-api 의 `mention-candidates` 를 사용할 것.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/search")
    fun searchUsers(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "displayName prefix (대소문자 무시). 빈 값=최근 가입 순.", example = "ji")
        @org.springframework.web.bind.annotation.RequestParam(required = false) q: String?,
        @Parameter(description = "1..20", example = "10")
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "10") limit: Int,
    ): List<UserSearchResponse> {
        val viewerId = currentUserId(jwt)
        return searchUsersUseCase.invoke(q, limit, viewerId).map {
            UserSearchResponse(userId = it.userId, handle = it.handle, displayName = it.displayName, avatarUrl = it.avatarUrl)
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
            handle = r.handle,
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

    @Operation(
        summary = "아바타 이미지 업로드 (multipart)",
        description = """
            Settings → Profile → "Upload new". `multipart/form-data` 의 `file` 파트로 이미지 1장 전송.

            **검증**
            - Content-Type 은 `image/png` / `image/jpeg` / `image/webp` / `image/gif` 중 하나
            - 크기 ≤ 5MB
            - 빈 파일 거부

            **응답**: 갱신된 전체 프로필(`MyProfileResponse`) — 클라이언트가 *덮어쓰기 다시 GET* 불필요.

            **부수효과**: 기존 avatarUrl 이 우리 storage 의 URL 이면 함께 삭제(orphan 방지). 외부 URL(예: GitHub avatar) 이면 그대로 무시.
        """
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
        // 갱신된 프로필을 전체 반환 — FE 가 한 번의 응답으로 화면 갱신
        val r = getMyProfileUseCase.invoke(userId)
        return MyProfileResponse(
            userId = r.userId, email = r.email, handle = r.handle, displayName = r.displayName,
            avatarUrl = r.avatarUrl, bio = r.bio, status = r.status,
            pendingDeletionAt = r.pendingDeletionAt, createdAt = r.createdAt,
            language = r.language, timezone = r.timezone, theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "아바타 제거",
        description = """
            Settings → Profile → "Remove". `user.avatarUrl` 을 `null` 로 설정.
            기존 storage 파일도 함께 삭제(우리 prefix 인 경우). 멱등 — 이미 없어도 200.

            avatar 비우기의 *유일한 길* — PATCH 에선 `clearAvatar` flag 를 의도적으로 제거(파일 라이프사이클이 단순 값 수정과 다름).
        """
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
            userId = r.userId, email = r.email, handle = r.handle, displayName = r.displayName,
            avatarUrl = r.avatarUrl, bio = r.bio, status = r.status,
            pendingDeletionAt = r.pendingDeletionAt, createdAt = r.createdAt,
            language = r.language, timezone = r.timezone, theme = r.theme.name,
            defaultWorkspaceId = r.defaultWorkspaceId,
        )
    }

    @Operation(
        summary = "내 OAuth 연결 해제 (Disconnect)",
        description = """
            Settings → Connections → Disconnect 버튼에 대응. **본인만**.

            **가드**: 마지막 남은 로그인 수단이면 거부(`409 Conflict`).
            현재 OAuth 로만 가입 가능하므로 마지막 connection 을 끊으면 *계정 락아웃* 이 발생한다.
            (향후 비밀번호 가입 도입 시 이 가드는 *비밀번호 없는 경우* 로 좁아질 예정.)

            **멱등 아님**: 이미 없는 provider 는 `404 Not Found`. SPA 가 *현재 연결된 것만* Disconnect 버튼을 노출해 자연스럽게 회피.

            **세션과의 관계**: 끊긴 provider 로 발급된 *기존 refresh token 은 무효화하지 않음* —
            사용자가 명시적으로 *Sign out everywhere* 를 누르도록 액션 분리.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(
            responseCode = "404", description = "본인 카탈로그에 해당 provider 없음(또는 unknown provider 문자열)",
            content = [Content()]
        ),
        ApiResponse(
            responseCode = "409", description = "마지막 남은 로그인 수단 — 해제 불가",
            content = [Content()]
        )
    )
    @DeleteMapping("/me/connections/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnect(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "provider 코드(대소문자 무시)", example = "GITHUB")
        @PathVariable provider: String,
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

    @Operation(
        summary = "내 활성 세션 목록 (Settings → Security → Sessions)",
        description = """
            현재 로그인된 모든 디바이스 세션. 한 *세션* = 한 refresh-token *family* (rotation 으로 row 가 바뀌어도 같은 family).

            **포함**: revoked=null AND expires_at > now 인 row 들. family 별로 *가장 최신* 1건만.
            **정렬**: lastUsedAt DESC NULLS LAST, createdAt DESC.

            **세션 식별자**: 응답의 `sessionId` 는 family UUID. `DELETE /sessions/{sessionId}` path 에 사용.

            **참고**: 현재 디바이스 식별(`current=true`) 은 *클라이언트가 sessionId 를 안 후에야* 가능 — 지금은 응답에 포함 X. 클라이언트가 로그인 응답에서 sessionId 를 저장했다가 비교하는 방식 권장.
        """
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
        description = """
            지정한 sessionId(=family) 의 모든 refresh row 를 revoke. 다음 access 토큰 만료 후 (또는 즉시 refresh 시도 시) 그 디바이스는 강제 로그아웃.

            **가드**: 본인 소유 family 만 종료 가능. 모르는/타인 sessionId → 404 (정보 노출 회피).
            **멱등**: 이미 revoke 된 family 도 404 (동일 효과 — 이미 활성 아님).
        """
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
        description = """
            현재 디바이스 *포함* 모든 refresh family 를 revoke. 토큰 탈취 의심 시 핵심 액션.

            **부수효과**: 호출자 자신의 refresh 토큰도 무효화됨 → access 만료 후 강제 재로그인.
            **멱등**: 이미 active 가 없어도 200.
        """
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

    // 알림 설정 API 는 **noti-api 로 이동** (2026-06-10).
    //   GET / PATCH /api/v1/users/me/notification-settings → 게이트웨이가 noti-api(8082) 로 라우팅.
    //   iam-api 는 user lifecycle 만 보유. 채널×카테고리 모델 + 발송 정책은 noti-api 가 소유.

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}
