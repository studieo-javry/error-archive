package org.studieojavry.iamapi.demo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.demo.application.GuestLoginUseCase
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.demo.presentation.dto.GuestTokenResponse

/**
 * "로그인 없이 둘러보기" — GitHub OAuth 없이 임시 게스트 세션을 발급한다. **public**(인증 불필요).
 * 면접관/인사담당자가 회원가입 없이 서비스를 둘러볼 수 있게 하는 진입점.
 * gateway 에서 per-IP rate-limit 가 걸려 남용(계정 폭주)을 막는다.
 */
@Tag(
    name = "auth-guest",
    description = "로그인 없이 둘러보기 — OAuth 우회 임시 게스트 토큰 발급(public). 데모 비활성 시 404.",
)
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
class GuestLoginController(
    private val guestLoginUseCase: GuestLoginUseCase,
    private val props: DemoProperties,
) {

    @Operation(
        summary = "게스트 둘러보기 토큰 발급 (OAuth 우회)",
        description = """
            회원가입/로그인 없이 **공유 데모 워크스페이스**를 둘러볼 수 있는 임시 세션을 발급한다.
            방문자마다 별도 임시 사용자(`게스트-XXXX`)가 생성돼 작성자가 구분되며, 데모 워크스페이스에
            `WRITE` 로 가입된다(에러케이스/댓글 생성 가능, 업로드/발행/초대 불가).

            응답의 access 토큰은 `roles=["GUEST"]` 를 실어 **refresh 없이** 짧게 유효하다(만료 시 재발급).
            생성한 콘텐츠·게스트 계정은 **매일 초기화**된다.

            **인증**: 불필요. **비활성(prod 등)**: 404.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "게스트 토큰 + 진입 워크스페이스"),
        ApiResponse(responseCode = "404", description = "데모 둘러보기 비활성", content = [Content()]),
    )
    @PostMapping("/guest")
    fun guest(): GuestTokenResponse {
        if (!props.enabled) throw ResponseStatusException(HttpStatus.NOT_FOUND, "demo access disabled")
        val r = guestLoginUseCase.invoke()
        return GuestTokenResponse(
            tokenType = "Bearer",
            accessToken = r.accessToken,
            accessTokenExpiresAt = r.accessTokenExpiresAt,
            userId = r.userId,
            workspaceId = r.workspaceId,
            workspaceSlug = r.workspaceSlug,
            displayName = r.displayName,
        )
    }
}
