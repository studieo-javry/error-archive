package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace

/**
 * "이 레포의 근거를 이 Case 에 붙여도 되나" — 사람이 붙일 때·자동으로 붙일 때 모두 이 규칙 하나를 쓴다.
 *
 * **규칙**: Case 의 공간([GithubSpace.of])에 ACTIVE 연결이 있고, 그 연결이 이 repo 를 "사용 선택" 했는가.
 *  (+ resolve 단계에서 repo 활성·설치 ACTIVE 를 함께 본다.)
 *
 * 한 설치가 개인 + 여러 워크스페이스에 동시 연결될 수 있으므로, 더 이상 "설치의 범위" 가 아니라
 * "연결(connection)이 그 공간에서 이 repo 를 선택했는가" 가 기준이다. 한 공간에서 떨어진(해제·미선택)
 * 레포의 비공개 이벤트가 다른 공간 멤버에게 새지 않도록 막는다.
 */
@Component
class GithubConnectionGuard(
    private val installations: GithubInstallationRepositoryPort,
    private val connections: GithubConnectionRepositoryPort,
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

    /** Case 공간의 ACTIVE 연결이 이 repo 를 선택했는가. */
    fun selects(errorCase: ErrorCase, repoId: Long): Boolean =
        connections.isRepoSelectedInSpace(GithubSpace.of(errorCase), repoId)

    fun requireSelected(errorCase: ErrorCase, repoId: Long) {
        if (!selects(errorCase, repoId)) {
            throw GithubScopeMismatchException(
                "이 Case 공간에 활성 GitHub 연결이 없거나 레포가 선택되지 않음 (case=${errorCase.id}, repo=$repoId)",
            )
        }
    }
}
