package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import java.time.Instant

/**
 * **Internal-only** — insight-api 가 제품 참여(engagement) 지표 중 "활성화"(가입 → 첫 케이스 지연/활성화율)를
 * 계산할 때 사용. gateway 미라우팅.
 *
 * 개인정보 최소화: userId + 가입시각(createdAt) 만 반환(이메일·이름 등 제외). 게스트는 제외.
 */
@Tag(name = "users-internal", description = "service-to-service: 가입 코호트 조회(활성화 지표용).")
@RestController
@RequestMapping("/internal/users")
class InternalUserCohortController(
    private val userRepository: UserRepositoryPort,
) {
    @Operation(
        summary = "최근 가입 코호트 조회",
        description = "`since`(ISO-8601 instant) 이후 가입한 비게스트 사용자의 (userId, createdAt) 목록. " +
            "insight-api 가 '가입 → 첫 케이스' 지연·활성화율 코호트로 사용한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "since 파싱 실패(ISO-8601 아님)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/signups")
    fun signups(
        @Parameter(description = "이 시각 이후 가입분만. ISO-8601 instant 예: 2026-10-01T00:00:00Z")
        @RequestParam since: String,
        @Parameter(description = "최대 반환 수(안전 캡). 기본 5000.")
        @RequestParam(required = false, defaultValue = "5000") limit: Int,
    ): List<SignupResponse> {
        val sinceTs = runCatching { Instant.parse(since) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid 'since' (ISO-8601 instant expected): $since")
        }
        return userRepository.findSignupsSince(sinceTs, limit)
            .map { SignupResponse(requireNotNull(it.id) { "persisted user must have id" }, it.createdAt) }
    }

    data class SignupResponse(val userId: Long, val createdAt: Instant)
}
