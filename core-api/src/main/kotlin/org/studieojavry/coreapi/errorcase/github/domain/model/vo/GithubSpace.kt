package org.studieojavry.coreapi.errorcase.github.domain.model.vo

import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase

/**
 * GitHub 연결이 붙는 "공간". 개인(개인 Case) 또는 워크스페이스 둘 중 하나.
 *
 * 과거 [GithubInstallation.workspaceId] 하나로 표현하던 범위를 공간 개념으로 승격한 것.
 * 한 설치가 개인 + 여러 워크스페이스에 동시 연결될 수 있으므로 "설치의 범위" 가 아니라
 * "연결(connection)의 공간" 이 권한 판단의 기준이 된다.
 */
sealed interface GithubSpace {
    data class Personal(val ownerUserId: Long) : GithubSpace

    data class Workspace(val workspaceId: Long) : GithubSpace

    companion object {
        /** Case 의 공간. 워크스페이스 Case → Workspace, 개인 Case → 소유자 기준 Personal. */
        fun of(errorCase: ErrorCase): GithubSpace =
            errorCase.meta.workspaceId?.let(::Workspace) ?: Personal(errorCase.ownerUserId)
    }
}
