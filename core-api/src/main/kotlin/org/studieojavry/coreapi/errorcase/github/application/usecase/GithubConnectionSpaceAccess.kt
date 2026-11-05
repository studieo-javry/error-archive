package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 연결(공간)에 대한 쓰기 권한 확인 — 개인 공간은 **본인만**, 워크스페이스 공간은 **ADMIN** 만.
 *
 * 개인 공간을 남이 건드리려 하면 존재를 숨기려 404([GithubConnectionNotFoundException])로 응답한다
 * (다른 사용자의 연결 id 존재 여부가 새지 않게). 워크스페이스는 기존 관례대로 403(WorkspaceAccessDenied).
 */
internal fun requireSpaceAccess(workspaceQuery: WorkspaceQueryPort, userId: Long, space: GithubSpace) {
    when (space) {
        is GithubSpace.Personal ->
            if (space.ownerUserId != userId) {
                throw GithubConnectionNotFoundException("연결을 찾을 수 없습니다")
            }
        is GithubSpace.Workspace -> requireWorkspaceAdmin(workspaceQuery, userId, space.workspaceId)
    }
}
