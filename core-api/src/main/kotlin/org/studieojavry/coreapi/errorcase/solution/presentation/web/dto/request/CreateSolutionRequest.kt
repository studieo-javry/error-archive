package org.studieojavry.coreapi.errorcase.solution.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution


@Schema(description = "Solution 생성 요청. stepIds 는 모두 같은 케이스의 step 이어야 한다. 순서가 유의미.")
data class CreateSolutionRequest(
    @field:Schema(description = "한 문장 요약", example = "캐시 무효화 + cleanup 둘 다 적용", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    @field:NotBlank @field:Size(max = 200)
    val title: String,

    @field:Schema(description = "묶을 step id 목록(사용자가 선택한 순서)", example = "[3, 5]", requiredMode = Schema.RequiredMode.REQUIRED)
    val stepIds: List<Long>,
)
