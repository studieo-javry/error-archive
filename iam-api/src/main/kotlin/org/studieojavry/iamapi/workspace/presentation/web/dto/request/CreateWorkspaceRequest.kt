package org.studieojavry.iamapi.workspace.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "워크스페이스 생성 요청. 생성자가 자동으로 ADMIN 멤버 등록됨.")
data class CreateWorkspaceRequest(
    @field:Schema(
        description = "표시 이름. 영문/숫자/공백/하이픈 만 허용. (kebab-case slug 무손실 정규화 보장)",
        example = "Team Alpha",
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 1,
        maxLength = 50,
    )
    @field:NotBlank
    @field:Size(min = 1, max = 50)
    @field:Pattern(
        regexp = "^[A-Za-z0-9 \\-]+$",
        message = "name must contain only ASCII letters, digits, spaces, hyphens",
    )
    val name: String,

    @field:Schema(
        description = "URL slug (kebab-case). lowercase alnum + 단일 hyphen 구분. 생성 후 변경 불가. unique.",
        example = "team-alpha",
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 2,
        maxLength = 40,
    )
    @field:NotBlank
    @field:Size(min = 2, max = 40)
    @field:Pattern(
        regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
        message = "slug must be kebab-case: lowercase alnum, single hyphens, no leading/trailing/double hyphen",
    )
    val slug: String,
)
