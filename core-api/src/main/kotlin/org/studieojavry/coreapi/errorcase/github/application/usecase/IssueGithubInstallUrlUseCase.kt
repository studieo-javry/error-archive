package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * "GitHub 연결" 시작 — App 설치 페이지 URL(+서명된 state) 발급.
 * 워크스페이스로 연결하려면 그 워크스페이스 ADMIN 이어야 한다(레포 이벤트가 워크스페이스 Case 에 붙을 수 있게 되므로).
 */
@Component
@ConditionalOnGithubEnabled
class IssueGithubInstallUrlUseCase(
    private val properties: GithubAppProperties,
    private val stateCodec: GithubInstallStateCodec,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    fun invoke(userId: Long, workspaceId: Long?): String {
        workspaceId?.let { requireWorkspaceAdmin(workspaceQuery, userId, it) }
        return UriComponentsBuilder.fromUriString(properties.webBaseUrl)
            .path("/apps/{slug}/installations/new")
            .queryParam("state", stateCodec.issue(userId, workspaceId))
            .buildAndExpand(properties.appSlug)
            .toUriString()
    }
}

internal fun requireWorkspaceAdmin(workspaceQuery: WorkspaceQueryPort, userId: Long, workspaceId: Long) {
    val role = workspaceQuery.getViewerRole(userId, workspaceId)
    if (role == null || !role.canAdminister()) {
        throw WorkspaceAccessDeniedException("워크스페이스 ADMIN 만 GitHub 를 연결할 수 있습니다: workspaceId=$workspaceId")
    }
}
