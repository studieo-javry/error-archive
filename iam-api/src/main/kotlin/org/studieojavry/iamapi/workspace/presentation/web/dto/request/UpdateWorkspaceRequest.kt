package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "워크스페이스 부분 수정. null/미포함=유지. ADMIN 만.")
data class UpdateWorkspaceRequest(
    @field:Schema(
        description = "새 이름. null=유지.",
        example = "team-alpha-v2",
        minLength = 1,
        maxLength = 50,
    )
    @field:Size(min = 1, max = 50)
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
