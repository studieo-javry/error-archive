package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "초대 수락 요청. 토큰을 capability 로 사용해 멤버 가입.")
data class AcceptInvitationRequest(
    @field:Schema(
        description = "초대 토큰(EMAIL/LINK 공통). EMAIL 은 *원자적 단일사용* — 동시 수락 중 하나만 성공.",
        example = "inv_xyz123abc",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotBlank
    val token: String,
)
