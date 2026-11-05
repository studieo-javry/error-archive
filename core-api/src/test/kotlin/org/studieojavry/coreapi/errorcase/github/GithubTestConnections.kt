package org.studieojavry.coreapi.errorcase.github

import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import java.time.Instant

/**
 * 통합테스트 공용 — 설치를 한 공간(워크스페이스/개인)에 연결하고 레포를 선택한다.
 * 과거 `GithubInstallation.connect(..., workspaceId, ...)` 로 표현하던 범위를 새 연결 모델로 시드한다.
 */
fun wireGithubConnection(
    connections: GithubConnectionRepositoryPort,
    installationId: Long,
    workspaceId: Long?,
    installedBy: Long,
    repoIds: List<Long>,
) {
    val space = workspaceId?.let(GithubSpace::Workspace) ?: GithubSpace.Personal(installedBy)
    val connection = connections.save(GithubConnection.create(installationId, space, installedBy))
    connections.replaceSelectedRepos(requireNotNull(connection.connectionId), repoIds, Instant.now())
}
