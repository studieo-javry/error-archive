package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * "이미 설치된 GitHub App 을 (이) 공간에 연결" 시작 — **OAuth 인가** URL(+서명된 state) 발급.
 *
 * 왜 installations/new 가 아니라 login/oauth/authorize 인가:
 * GitHub 는 계정당 App 설치를 1건만 허용한다. 이미 설치된 계정에서 installations/new 로 가면 GitHub 는
 * 새 설치 대신 **기존 설치 Configure 화면**(바꿀 게 없어 Save 비활성)으로 보내 콜백이 오지 않는다(= 막다른 길).
 * 대신 OAuth 인가로 **사용자 토큰**을 받으면, 그 사용자가 이미 접근 가능한 설치 목록을 그대로 후보로 조회할 수 있어
 * 설치 화면을 거치지 않고 연결을 완료할 수 있다.
 *
 * 워크스페이스로 연결하려면 그 워크스페이스 ADMIN 이어야 한다. state 는 [GithubInstallStateCodec] 를 공유한다
 * (누가·어느 공간으로 시작했나를 서명; 완료 시점에 재검증).
 */
@Component
@ConditionalOnGithubEnabled
class IssueGithubAuthorizeUrlUseCase(
    private val properties: GithubAppProperties,
    private val stateCodec: GithubInstallStateCodec,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    /** [remember]=true 면 "6개월 기억" 동의를 state 에 실어, 왕복 뒤 후보 조회에서 refresh 토큰까지 저장하게 한다. */
    fun invoke(userId: Long, workspaceId: Long?, remember: Boolean = false): String {
        workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, userId, it) }
        return UriComponentsBuilder.fromUriString(properties.webBaseUrl)
            .path("/login/oauth/authorize")
            .queryParam("client_id", properties.clientId)
            .queryParam("state", stateCodec.issue(userId, workspaceId, remember))
            .build()
            .toUriString()
    }
}
