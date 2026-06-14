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
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus

/**
 * **Internal-only** — noti-api 가 알림 발송 시 recipient 의 email/displayName 을 조회.
 * gateway 가 라우팅하지 않으므로 외부 접근 X. internal JWT(iss=noti-api, aud=iam-api) 검증 필요.
 *
 * 응답에 status 도 포함하여 호출자가 *비활성/탈퇴* 사용자에게 발송을 skip 할 수 있게 한다.
 */
@Tag(name = "users-internal", description = "service-to-service: 사용자 contact 조회.")
@RestController
@RequestMapping("/internal/users")
class InternalUserContactController(
    private val userRepository: UserRepositoryPort,
) {

    @Operation(
        summary = "단일 사용자 contact 조회",
        description = "noti-api 가 멘션 알림의 email/push 채널 발송 직전에 호출."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "사용자 없음", content = [Content()]),
    )
    @GetMapping("/{userId}/contact")
    fun getContact(@PathVariable userId: Long): UserContactResponse {
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found: $userId")
        return UserContactResponse(
            userId = user.id!!,
            email = user.email?.value,
            displayName = user.displayName,
            status = user.status.name,
            active = user.status == UserStatus.ACTIVE,
        )
    }

    data class UserContactResponse(
        val userId: Long,
        val email: String?,
        val displayName: String,
        val status: String,
        val active: Boolean,
    )
}