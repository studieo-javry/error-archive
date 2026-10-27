package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus

/**
 * "이 레포의 근거를 이 Case 에 붙여도 되나" — 사람이 붙일 때와 자동으로 붙일 때 모두 이 규칙 하나를 쓴다.
 *
 * **설치 범위 = Case 범위** 여야 한다.
 *  - 워크스페이스 W 에 연결된 설치 → W 의 Case 에만
 *  - 개인 설치(연결자 A)          → A 가 주인인 개인 Case(workspaceId 없음)에만
 *
 * 두 워크스페이스에 모두 속한 사람이 A 의 비공개 레포 Issue 를 B 의 Case 에 붙여
 * B 멤버 전원에게 노출시키는 경로를 원천 차단하기 위함.
 */
@Component
class GithubScopeGuard(
    private val installations: GithubInstallationRepositoryPort,
) {
    data class ResolvedRepository(val repository: GithubRepository, val installation: GithubInstallation)

    /** `owner/repo` → 활성 레포 + 활성 설치. 없으면 [GithubRepositoryNotConnectedException]. */
    fun resolve(fullName: String): ResolvedRepository {
        val repo = installations.findActiveRepositoryByFullName(fullName)
            ?: throw GithubRepositoryNotConnectedException("GitHub 연결에 포함되지 않은 레포: $fullName")
        val installation = installations.findById(repo.installationId)
            ?.takeIf { it.status == GithubInstallationStatus.ACTIVE }
            ?: throw GithubRepositoryNotConnectedException("레포의 GitHub 연결이 활성 상태가 아님: $fullName")
        return ResolvedRepository(repo, installation)
    }

    fun isSameScope(installation: GithubInstallation, errorCase: ErrorCase): Boolean {
        val installationWs = installation.workspaceId
        val caseWs = errorCase.meta.workspaceId
        return if (installationWs != null) {
            caseWs == installationWs
        } else {
            caseWs == null && errorCase.ownerUserId == installation.installedByUserId
        }
    }

    fun requireSameScope(installation: GithubInstallation, errorCase: ErrorCase) {
        if (!isSameScope(installation, errorCase)) {
            throw GithubScopeMismatchException(
                "레포 연결 범위와 Case 범위가 다름 (installation=${installation.installationId}, case=${errorCase.id})",
            )
        }
    }
}
