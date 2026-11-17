package org.studieojavry.iamapi.agentconnection.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "로컬 커넥터 npm 패키지의 최신 배포 버전. 설치 명령은 @latest 를 쓰므로 이 값은 표시·고정용이다.")
data class ConnectorVersionResponse(
    @Schema(description = "npm 패키지명(스코프 포함)", example = "@error-archive/agent")
    val packageName: String,
    @Schema(description = "최신 배포 버전(dist-tags.latest). 조회 실패(토큰 없음·네트워크·비공개)면 null — FE 는 '확인 실패' 상태로 표시.", example = "1.11.2", nullable = true)
    val latest: String?,
)
