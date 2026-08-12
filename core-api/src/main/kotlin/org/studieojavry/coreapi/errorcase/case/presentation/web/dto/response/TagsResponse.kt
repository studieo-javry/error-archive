package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "태그 추가/제거 응답. `added`/`removed` 둘 중 하나가 실제로 변경됐는지 표시. `allTags` 는 해당 케이스의 *현재 전체 태그* 목록.")
data class TagsResponse(
    val tag: String,
    val added: Boolean,
    val removed: Boolean,
    val allTags: List<String>,
)