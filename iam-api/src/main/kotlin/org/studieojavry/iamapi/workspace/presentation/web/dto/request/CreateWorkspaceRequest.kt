package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "워크스페이스 생성 요청. 생성자가 자동으로 ADMIN 멤버 등록됨.")
data class CreateWorkspaceRequest(
    @field:Schema(
        description = "워크스페이스 이름.",
        example = "team-alpha",
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 1,
        maxLength = 50,
    )
    @field:NotBlank
    @field:Size(min = 1, max = 50)
    val name: String,
)
