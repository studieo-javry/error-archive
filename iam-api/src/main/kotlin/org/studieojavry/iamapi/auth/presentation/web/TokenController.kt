package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.auth.application.command.RefreshAccessTokenCommand
import org.studieojavry.iamapi.auth.application.usecase.LogoutUseCase
import org.studieojavry.iamapi.auth.application.usecase.RefreshAccessTokenUseCase
import org.studieojavry.iamapi.auth.config.AuthCookieProperties
import org.studieojavry.iamapi.auth.infrastructure.security.AuthCookieFactory
import org.studieojavry.iamapi.auth.presentation.web.dto.response.TokenResponse
import java.time.Duration
import java.time.Instant

@Tag(name = "auth-token", description = "access/refresh 토큰 라이프사이클. 두 엔드포인트 모두 refresh 쿠키 기반(인증 헤더 불요).")
@RestController
@RequestMapping("/api/v1/auth")
class TokenController(
    private val refreshAccessTokenUseCase: RefreshAccessTokenUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val cookieFactory: AuthCookieFactory,
    private val cookieProperties: AuthCookieProperties
) {

    @Operation(
        summary = "access 토큰 갱신",
        description = """
            httpOnly 쿠키로 보관된 refresh 토큰을 검증해 새 access 토큰을 발급한다.
            응답 본문엔 새 access 토큰, 응답 헤더 `Set-Cookie` 로 회전된 refresh 토큰.

            **언제 사용**: access 토큰 만료 직전/직후. 프런트가 401 받으면 이 엔드포인트로 갱신 후 원 요청 재시도.
            **인증**: 헤더 불필요(refresh 쿠키만으로 인증).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신 성공"),
        ApiResponse(responseCode = "401", description = "refresh 쿠키 없음/만료/위조", content = [Content()])
    )
    @SecurityRequirements
    @PostMapping("/refresh")
    fun refresh(
        @Parameter(hidden = true) request: HttpServletRequest,
        @Parameter(hidden = true) response: HttpServletResponse
    ): ResponseEntity<TokenResponse> {
        val refreshToken = readRefreshTokenCookie(request)
            ?: throw RefreshAccessTokenUseCase.InvalidRefreshTokenException("refresh token cookie missing")

        val result = refreshAccessTokenUseCase.invoke(RefreshAccessTokenCommand(refreshToken))

        val refreshTtl = Duration.between(Instant.now(), result.refreshTokenExpiresAt)
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieFactory.refreshTokenCookie(
                value = result.refreshToken,
                ttl = refreshTtl,
                persistent = result.rememberMe
            ).toString()
        )

        return ResponseEntity.ok(
            TokenResponse(
                tokenType = "Bearer",
                accessToken = result.accessToken,
                accessTokenExpiresAt = result.accessTokenExpiresAt,
                userId = result.userId
            )
        )
    }

    @Operation(
        summary = "로그아웃",
        description = """
            서버측 refresh 토큰을 폐기하고, 응답 `Set-Cookie` 로 클라이언트 refresh 쿠키도 만료시킨다.
            access 토큰(들고 있는 것)은 만료까지 그대로 유효하다 — 짧은 수명(분 단위)으로 운영.

            **인증**: 헤더 있으면 사용자 식별에 사용, 없어도 동작(쿠키만으로 폐기).
        """
    )
    @ApiResponses(ApiResponse(responseCode = "204", description = "폐기 완료(멱등)"))
    @SecurityRequirements
    @PostMapping("/logout")
    fun logout(
        @Parameter(hidden = true) request: HttpServletRequest,
        @Parameter(hidden = true) response: HttpServletResponse,
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Void> {
        val refreshToken = readRefreshTokenCookie(request)
        val userId = jwt?.subject?.toLongOrNull()
        logoutUseCase.invoke(refreshToken = refreshToken, userId = userId)

        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshTokenCookie().toString())
        return ResponseEntity.noContent().build()
    }

    private fun readRefreshTokenCookie(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == cookieProperties.refreshTokenName }?.value
            ?.takeIf { it.isNotBlank() }
}
