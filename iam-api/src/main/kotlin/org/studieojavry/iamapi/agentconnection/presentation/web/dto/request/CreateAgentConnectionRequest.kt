package org.studieojavry.iamapi.agentconnection.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "agent 연결 토큰 발급 요청. 토큰 원문은 응답에서 한 번만 노출된다.")
data class CreateAgentConnectionRequest(
    @field:Size(max = 120)
    @Schema(description = "연결을 식별하는 사람이 읽을 라벨(예: 로컬 장비·레포 이름).", example = "MacBook · error-archive")
    val label: String? = null,

    @Schema(
        description = "허용 범위. 생략/빈 값이면 P0 기본 전체(case:read, capture:read, capture:write, artifact:write).",
        example = "[\"capture:read\",\"capture:write\",\"artifact:write\",\"case:read\"]",
    )
    val scopes: List<String>? = null,
)
