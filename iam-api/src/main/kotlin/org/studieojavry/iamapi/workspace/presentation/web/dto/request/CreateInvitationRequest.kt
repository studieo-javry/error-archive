package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

@Schema(description = "워크스페이스 초대 생성 요청. ADMIN 만.")
data class CreateInvitationRequest(
    @field:Schema(
        description = "초대 타입. EMAIL=지정 이메일로 SMTP 발송, *일회용* / LINK=URL 만 발급, *다회용*.",
        example = "EMAIL",
        requiredMode = Schema.RequiredMode.REQUIRED,
        allowableValues = ["EMAIL", "LINK"],
    )
    @field:NotBlank
    val type: String,

    @field:Schema(
        description = "초대 받을 멤버의 역할. **ADMIN 초대 불가** (생성·승격은 별도 endpoint).",
        example = "WRITE",
        requiredMode = Schema.RequiredMode.REQUIRED,
        allowableValues = ["READ", "WRITE"],
    )
    @field:NotBlank
    val role: String,

    @field:Schema(
        description = "초대 대상 이메일. EMAIL 타입일 때 권장. LINK 타입은 null OK.",
        example = "newmember@example.com",
    )
    @field:Email
    val email: String?,

    @field:Schema(
        description = "만료까지의 시간(시간 단위). null=서버 기본값.",
        example = "48",
        minimum = "1",
    )
    @field:Min(1)
    val expiresInHours: Int?,
)
