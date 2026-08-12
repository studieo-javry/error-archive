package org.studieojavry.iamapi.auth.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort

/**
 * **Internal-only** — insight-api 가 사용자 timezone 조회 시 사용. gateway 미라우팅.
 *
 * timezone 미설정 사용자는 `null` 반환 → 호출자는 UTC 로 폴백.
 */
@Tag(name = "users-internal", description = "service-to-service: 사용자 preferences 조회.")
@RestController
@RequestMapping("/internal/users")
class InternalUserPreferencesController(
    private val userRepository: UserRepositoryPort,
) {
    @Operation(summary = "단일 사용자 preferences (timezone 등) 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "사용자 없음", content = [Content()]),
    )
    @GetMapping("/{userId}/preferences")
    fun getPreferences(@PathVariable userId: Long): UserPreferencesResponse {
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found: $userId")
        return UserPreferencesResponse(
            userId = user.id!!,
            timezone = user.timezone,
            language = user.language,
        )
    }

    data class UserPreferencesResponse(
        val userId: Long,
        val timezone: String?,
        val language: String?,
    )
}
