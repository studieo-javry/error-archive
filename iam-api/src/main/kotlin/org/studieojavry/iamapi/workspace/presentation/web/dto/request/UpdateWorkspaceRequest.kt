package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "워크스페이스 부분 수정. null/미포함=유지. ADMIN 만. slug 는 변경 불가 (외부 URL 불변).")
data class UpdateWorkspaceRequest(
    @field:Schema(
        description = "새 이름. null=유지. 영문/숫자/공백/하이픈 만 허용.",
        example = "Team Alpha v2",
        minLength = 1,
        maxLength = 50,
    )
    @field:Size(min = 1, max = 50)
    @field:Pattern(
        regexp = "^[A-Za-z0-9 \\-]+$",
        message = "name must contain only ASCII letters, digits, spaces, hyphens",
    )
    val name: String?,

    @field:Schema(
        description = "워크스페이스 알림 전송 활성화. null=유지.",
        example = "true",
    )
    val notificationEnabled: Boolean?,

    @field:Schema(
        description = "워크스페이스 기본 타임존(IANA TZ id). null=유지.",
        example = "Asia/Seoul",
        maxLength = 64,
    )
    @field:Size(max = 64)
    val defaultTimezone: String?,
)
