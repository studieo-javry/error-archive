package org.studieojavry.iamapi.agentconnection.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.agentconnection.application.usecase.GetConnectorLatestVersionUseCase
import org.studieojavry.iamapi.agentconnection.config.AgentConnectorProperties
import org.studieojavry.iamapi.agentconnection.presentation.web.dto.response.ConnectorVersionResponse

/**
 * 설치 안내용 — 로컬 커넥터 npm 패키지의 최신 배포 버전 조회.
 * BE 가 읽기 권한 토큰으로 npm 을 조회·캐싱해 반환한다. 설치 명령 자체는 @latest 라 이 값에 의존하지 않으며,
 * 새 커넥터 버전을 배포해도 FE 수정/재배포가 필요 없다.
 * 비공개 패키지라 BE 경유가 필요하다 — 공개로 전환하면 FE 가 npm Registry 를 직접 조회해도 된다.
 */
@Tag(name = "agent-connector", description = "로컬 커넥터 설치 안내 메타(최신 버전).")
@RestController
@RequestMapping("/api/v1/me/agent-connections/connector-version")
class ConnectorVersionController(
    private val getConnectorLatestVersion: GetConnectorLatestVersionUseCase,
    private val properties: AgentConnectorProperties,
) {
    @GetMapping
    @Operation(
        summary = "최신 커넥터 버전 조회",
        description = "npm dist-tags.latest 를 BE 가 조회·캐싱해 반환. 조회 실패 시 latest=null(설치 명령은 @latest 라 영향 없음).",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공(latest 가 null 이면 확인 실패)"))
    fun latest(): ConnectorVersionResponse =
        ConnectorVersionResponse(
            packageName = properties.packageName,
            latest = getConnectorLatestVersion.latest(),
        )
}
