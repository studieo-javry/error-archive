package org.studieojavry.coreapi.errorcase.shared.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort


/**
 * 케이스 종속 리소스(step/solution/comment 등)의 권한 판정 헬퍼. 단일 진입점.
 *
 * **READ** 정책 (`Visibility` 기반):
 *  - owner            → 항상 통과
 *  - PUBLIC           → 로그인된 모든 사용자 통과 (인증은 controller 가 보장)
 *  - WORKSPACE        → 그 워크스페이스 멤버(READ+) 만 통과
 *  - PRIVATE          → owner 외 거부
 *
 * **WRITE** 정책 (도메인 수정):
 *  - owner            → 항상 통과
 *  - WORKSPACE 케이스 → 그 워크스페이스 WRITE+ 멤버 통과 (PUBLIC/PRIVATE 케이스의 수정은 owner 만)
 */
@Service
class ErrorCaseAccess(
    private val workspaceQuery: WorkspaceQueryPort,
) {
    fun requireRead(errorCase: ErrorCase, requesterUserId: Long) {
        if (errorCase.ownerUserId == requesterUserId) return
        when (errorCase.visibility) {
            Visibility.PUBLIC -> return
            Visibility.WORKSPACE -> {
                val wsId = errorCase.meta.workspaceId
                if (wsId != null && workspaceQuery.getViewerRole(requesterUserId, wsId)?.canRead() == true) return
            }
            Visibility.PRIVATE -> { /* fall through to throw */ }
        }
        throw ErrorCaseAccessDeniedException("not allowed to read error case ${errorCase.id}")
    }

    /**
     * *요청자가 아닌* 사용자(멘션 알림 수신자 등)의 읽기 권한 — [requireRead] 와 같은 정책, 예외 없이.
     * [WorkspaceQueryPort.getViewerRole] 은 *현재 요청자* 의 토큰으로 iam 을 조회하므로 남에게 쓰면 안 된다
     * (요청자 역할이 그 사람 키로 캐시됨). 대신 멤버 목록에서 찾는다. iam 장애로 목록이 비면 false(알림 보류 쪽이 안전).
     */
    fun canUserRead(errorCase: ErrorCase, userId: Long): Boolean {
        if (errorCase.ownerUserId == userId) return true
        return when (errorCase.visibility) {
            Visibility.PUBLIC -> true
            Visibility.WORKSPACE -> {
                val wsId = errorCase.meta.workspaceId ?: return false
                workspaceQuery.listMembers(wsId).any {
                    it.userId == userId && WorkspaceQueryPort.WorkspaceRole.fromCode(it.role)?.canRead() == true
                }
            }
            Visibility.PRIVATE -> false
        }
    }

    fun requireWrite(errorCase: ErrorCase, requesterUserId: Long) {
        if (errorCase.ownerUserId == requesterUserId) return
        if (errorCase.visibility == Visibility.WORKSPACE) {
            val wsId = errorCase.meta.workspaceId
            if (wsId != null && workspaceQuery.getViewerRole(requesterUserId, wsId)?.canWriteContent() == true) return
        }
        throw ErrorCaseAccessDeniedException("write permission required for error case ${errorCase.id}")
    }

    /**
     * 워크스페이스 케이스를 **PUBLIC 으로 승격** 할 권한 — 워크스페이스 ADMIN 만.
     * 일반 멤버(READ/WRITE) 가 비공개 자산을 외부 노출하는 사고 방지. `revisitable §4` 와 일관.
     * workspaceId == null 케이스(개인) 의 PUBLIC 토글은 owner 자유이므로 본 함수는 호출하지 않는다.
     * **owner 라도 워크스페이스 ADMIN 이어야 한다** — §4 정책의 핵심.
     */
    fun requirePublicPromotion(errorCase: ErrorCase, requesterUserId: Long) {
        val wsId = errorCase.meta.workspaceId ?: return
        if (workspaceQuery.getViewerRole(requesterUserId, wsId)?.canAdminister() == true) return
        throw ErrorCaseAccessDeniedException(
            "workspace ADMIN required to promote case ${errorCase.id} to PUBLIC"
        )
    }
}
