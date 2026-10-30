package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess

/**
 * GitHub 근거의 "누가 보나 / 누가 바꾸나" 규칙 한곳.
 *
 * - 비공개 레포 근거 상세: 워크스페이스 Case 는 그 워크스페이스의 **현재** 멤버(역할 무관), 개인 Case 는 주인.
 *   레포를 워크스페이스에 공유한 건 관리자이고 공유 대상은 "지금 팀원"이라, 주인이어도 떠났으면 가린다.
 * - 근거 쓰기(연결·떼기·Step 꼬리표): Case 쓰기 권한 + 워크스페이스 Case 면 현재 멤버.
 *   떠난 주인이 팀 레포 주소를 넣어 보며(201/404) 비공개 Issue 존재를 알아내는 경로를 막는다.
 */
@Component
@ConditionalOnGithubEnabled
class GithubCaseAccessPolicy(
    private val access: ErrorCaseAccess,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    fun canSeePrivate(errorCase: ErrorCase, viewerUserId: Long): Boolean {
        val wsId = errorCase.meta.workspaceId ?: return errorCase.ownerUserId == viewerUserId
        return workspaceQuery.getViewerRole(viewerUserId, wsId) != null
    }

    /** 레포 정보를 모르면(장부에서 사라짐) 비공개로 간주. */
    fun isVisible(repo: GithubRepository?, canSeePrivate: Boolean): Boolean = repo?.isPrivate == false || canSeePrivate

    fun requireWrite(errorCase: ErrorCase, requesterUserId: Long) {
        access.requireWrite(errorCase, requesterUserId)
        val wsId = errorCase.meta.workspaceId ?: return
        if (workspaceQuery.getViewerRole(requesterUserId, wsId) == null) {
            throw WorkspaceAccessDeniedException("워크스페이스 멤버만 GitHub 근거를 바꿀 수 있습니다: caseId=${errorCase.id} workspaceId=$wsId")
        }
    }
}
