package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 본문 `#` 자동완성이 뒤질 수 있는 레포 — **Case 가 속한(속할) 공간에 선택된** 활성 레포. 레포 공개 여부와 무관.
 *  - 워크스페이스 W: W 의 ACTIVE 연결이 선택한 레포. 요청자는 W 의 **현재** 멤버여야 한다.
 *  - 개인(workspaceId 없음): 요청자 본인 개인 공간의 ACTIVE 연결이 선택한 레포.
 *
 * 근거 연결 규칙([GithubConnectionGuard] — 공간 연결 + 레포 선택)과 같은 경계라, 자동완성으로 고른 참조는
 * 그 Case 에서 그대로 연결할 수 있다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubReferenceScope(
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    fun repositories(userId: Long, workspaceId: Long?): List<GithubRepository> {
        if (workspaceId != null && workspaceQuery.getViewerRole(userId, workspaceId) == null) {
            throw WorkspaceAccessDeniedException("워크스페이스 멤버만 GitHub 참조를 찾을 수 있습니다: workspaceId=$workspaceId")
        }
        val space = workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(userId)
        return connections.findSelectedActiveRepositories(space).sortedBy { it.fullName.lowercase() }
    }
}
