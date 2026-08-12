package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "워크스페이스 멤버 역할 변경 요청. ADMIN 만. 마지막 ADMIN 강등 시도는 거부(403).")
data class ChangeMemberRoleRequest(
    @field:Schema(
        description = "변경할 역할. 마지막 ADMIN 을 READ/WRITE 로 강등하려 하면 403.",
        example = "WRITE",
        requiredMode = Schema.RequiredMode.REQUIRED,
        allowableValues = ["READ", "WRITE", "ADMIN"],
    )
    @field:NotBlank
    val role: String,
)
