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
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.auth.application.command.SocialLoginCommand
import org.studieojavry.iamapi.auth.application.command.StartOAuthFlowCommand
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.ProviderCredential
import org.studieojavry.iamapi.auth.application.usecase.SocialLoginUseCase
import org.studieojavry.iamapi.auth.application.usecase.StartOAuthFlowUseCase
import org.studieojavry.iamapi.auth.config.AuthCookieProperties
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider
import org.studieojavry.iamapi.auth.infrastructure.security.AuthCookieFactory
import org.studieojavry.iamapi.auth.presentation.web.dto.request.OAuthCallbackRequest
import org.studieojavry.iamapi.auth.presentation.web.dto.request.StartOAuthRequest
import org.studieojavry.iamapi.auth.presentation.web.dto.response.AuthorizeUrlResponse
import org.studieojavry.iamapi.auth.presentation.web.dto.response.TokenResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Tag(
    name = "auth-oauth",
    description = "SPA-driven OAuth(JSON, 302 아님). 시작/콜백 모두 public. state·복귀URL 은 httpOnly 쿠키 왕복."
)
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth/oauth")
class OAuthController(
    private val startOAuthFlowUseCase: StartOAuthFlowUseCase,
    private val socialLoginUseCase: SocialLoginUseCase,
    private val cookieFactory: AuthCookieFactory,
    private val cookieProperties: AuthCookieProperties
) {

    // TODO: device별 혹은 브라우저별 로그인 기록 남기기

    @Operation(
        summary = "OAuth 시작 (authorization URL 발급)",
        description = """
            소셜 로그인 시작. 서버가 state 생성·httpOnly 쿠키로 보관 → provider 의 authorization URL 반환.
            프런트는 그 URL 로 사용자를 보내면 된다.

            **returnUrl**(선택): 로그인 완료 후 돌아갈 SPA 내부 경로. 콜백 응답의 `redirectTo` 로 되돌아옴. open-redirect 방지를 위해 **상대경로(`/...`)만** 허용(`//`, `://`, `\\`, 공백/제어문자, 길이>512 모두 거절).

            **인증**: 불필요(로그인 전).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "authorizationUrl 반환"),
        ApiResponse(responseCode = "400", description = "provider 미지원 / 요청 검증 실패", content = [Content()])
    )
    @PostMapping("/{provider}/authorize")
    fun authorize(
        @Parameter(description = "소셜 프로바이더 코드(예: github)", example = "github") @PathVariable provider: String,
        @Valid @RequestBody request: StartOAuthRequest,
        @Parameter(hidden = true) response: HttpServletResponse
    ): AuthorizeUrlResponse {
        val result = startOAuthFlowUseCase.invoke(
            StartOAuthFlowCommand(
                provider = SocialProvider.fromCode(provider),
                redirectUri = request.redirectUri
            )
        )
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.oauthStateCookie(result.state).toString())
        // 복귀 경로는 OAuth 왕복 동안 쿠키로 보관(콜백에서 redirectTo 로 되돌려줌). 안전한 내부 경로만.
        sanitizeReturnUrl(request.returnUrl)?.let {
            response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.oauthReturnCookie(it).toString())
        }
        return AuthorizeUrlResponse(authorizationUrl = result.authorizationUrl)
    }

    @Operation(
        summary = "OAuth 콜백 → 토큰 발급",
        description = """
            provider 가 SPA 로 돌려보낸 `code`·`state` 를 받아 사용자 프로필을 조회·계정 연결 후 토큰 발급.
            응답엔 access 토큰 + `redirectTo`(authorize 단계의 returnUrl 재검증 후 반환).
            응답 헤더 `Set-Cookie` 로 refresh 토큰 + 사용한 state 쿠키 만료.

            **인증**: 불필요.
            **검증**: state 쿠키와 요청의 `state` 가 상수시간 비교로 일치해야 함.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토큰/redirectTo"),
        ApiResponse(responseCode = "400", description = "state 불일치 / code 교환 실패", content = [Content()])
    )
    @PostMapping("/{provider}/callback")
    fun callback(
        @Parameter(description = "소셜 프로바이더 코드", example = "github") @PathVariable provider: String,
        @Valid @RequestBody request: OAuthCallbackRequest,
        @Parameter(hidden = true) servletRequest: HttpServletRequest,
        @Parameter(hidden = true) response: HttpServletResponse
    ): ResponseEntity<TokenResponse> {
        validateState(servletRequest, request.state)
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredOAuthStateCookie().toString())

        // 보관해 둔 복귀 경로를 꺼내고(방어적으로 재검증), 쿠키는 만료시킨다.
        val redirectTo = sanitizeReturnUrl(readReturnCookie(servletRequest))
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredOAuthReturnCookie().toString())

        val ctx = org.studieojavry.iamapi.auth.infrastructure.security.ClientContext.from(servletRequest)
        val result = socialLoginUseCase.invoke(
            SocialLoginCommand(
                provider = SocialProvider.fromCode(provider),
                credential = ProviderCredential.AuthorizationCode(
                    code = request.code,
                    redirectUri = request.redirectUri
                ),
                rememberMe = request.rememberMe,
                deviceLabel = ctx.deviceLabel,
                userAgent = ctx.userAgent,
                ipAddress = ctx.ipAddress,
            )
        )

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
                userId = result.userId,
                redirectTo = redirectTo
            )
        )
    }

    private fun readReturnCookie(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == cookieProperties.oauthReturnName }?.value

    /**
     * open-redirect 방지: 앱 **내부 상대경로**만 허용한다. 위반 시 null(복귀 경로 무시 → SPA 기본 홈).
     *  - "/" 로 시작, "//"(프로토콜-상대)·"\\"·"://"(스킴) 금지, 공백/제어문자 금지(쿠키 안전성도 겸함).
     */
    private fun sanitizeReturnUrl(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length > 512) return null
        if (v.any { it.isWhitespace() || it.isISOControl() }) return null
        if (!v.startsWith("/")) return null
        if (v.startsWith("//")) return null
        if (v.contains('\\')) return null
        if (v.contains("://")) return null
        return v
    }

    private fun validateState(request: HttpServletRequest, presentedState: String) {
        val cookie = request.cookies?.firstOrNull { it.name == cookieProperties.oauthStateName }
            ?: throw IllegalStateException("oauth state cookie missing")
        if (!constantTimeEquals(cookie.value, presentedState)) {
            throw IllegalStateException("oauth state mismatch")
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
