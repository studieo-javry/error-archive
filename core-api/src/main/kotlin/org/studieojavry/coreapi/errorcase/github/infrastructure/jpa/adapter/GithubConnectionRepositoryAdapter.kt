package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubConnectionJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubConnectionRepositoryJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubInstallationJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubRepositoryJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubConnectionEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubConnectionRepositoryEntity
import java.time.Instant

@Component
class GithubConnectionRepositoryAdapter(
    private val connections: GithubConnectionJpaRepository,
    private val connectionRepos: GithubConnectionRepositoryJpaRepository,
    private val repositories: GithubRepositoryJpaRepository,
    private val installations: GithubInstallationJpaRepository,
) : GithubConnectionRepositoryPort {

    private val active = GithubConnectionStatus.ACTIVE

    override fun save(connection: GithubConnection): GithubConnection =
        connections.save(GithubConnectionEntity.from(connection)).toDomain()

    override fun findByConnectionId(connectionId: Long): GithubConnection? =
        connections.findById(connectionId).orElse(null)?.toDomain()

    override fun findActive(installationId: Long, space: GithubSpace): GithubConnection? =
        when (space) {
            is GithubSpace.Personal ->
                connections.findFirstByInstallationIdAndOwnerUserIdAndStatus(installationId, space.ownerUserId, active)
            is GithubSpace.Workspace ->
                connections.findFirstByInstallationIdAndWorkspaceIdAndStatus(installationId, space.workspaceId, active)
        }?.toDomain()

    override fun findAnyForSpaceAndInstallation(installationId: Long, space: GithubSpace): GithubConnection? =
        when (space) {
            is GithubSpace.Personal ->
                connections.findFirstByInstallationIdAndOwnerUserIdOrderByConnectionIdDesc(installationId, space.ownerUserId)
            is GithubSpace.Workspace ->
                connections.findFirstByInstallationIdAndWorkspaceIdOrderByConnectionIdDesc(installationId, space.workspaceId)
        }?.toDomain()

    override fun findActiveBySpaces(spaces: Collection<GithubSpace>): List<GithubConnection> {
        if (spaces.isEmpty()) return emptyList()
        val (ownerIds, workspaceIds) = decompose(spaces)
        return connections.findActiveBySpaces(active, ownerIds, workspaceIds).map { it.toDomain() }
    }

    override fun findActiveByInstallation(installationId: Long): List<GithubConnection> =
        connections.findAllByInstallationIdAndStatus(installationId, active).map { it.toDomain() }

    override fun isRepoSelectedInSpace(space: GithubSpace, repoId: Long): Boolean {
        val ownerUserId = (space as? GithubSpace.Personal)?.ownerUserId
        val workspaceId = (space as? GithubSpace.Workspace)?.workspaceId
        return connections.existsRepoSelectedInSpace(active, ownerUserId, workspaceId, repoId)
    }

    override fun spacesSelectingRepo(spaces: Collection<GithubSpace>, repoId: Long): Set<GithubSpace> {
        if (spaces.isEmpty()) return emptySet()
        val (ownerIds, workspaceIds) = decompose(spaces)
        val requested = spaces.toSet()
        return connections.connectionsSelectingRepo(active, ownerIds, workspaceIds, repoId)
            .map { it.toDomain().space }
            .filter { it in requested }
            .toSet()
    }

    override fun findSelectedActiveRepositories(space: GithubSpace): List<GithubRepository> {
        val connectionIds = findActiveBySpaces(listOf(space)).mapNotNull { it.connectionId }
        if (connectionIds.isEmpty()) return emptyList()
        val repoIds = connectionRepos.selectedRepoIds(connectionIds).toSet()
        if (repoIds.isEmpty()) return emptyList()
        val repos = repositories.findAllById(repoIds).map { it.toDomain() }.filter { it.isActive }
        if (repos.isEmpty()) return emptyList()
        val activeInstallationIds = installations.findAllById(repos.map { it.installationId }.toSet())
            .filter { it.status == GithubInstallationStatus.ACTIVE }
            .map { it.installationId }
            .toSet()
        return repos.filter { it.installationId in activeInstallationIds }
    }

    override fun selectedRepoIds(connectionId: Long): Set<Long> =
        connectionRepos.findAllByConnectionId(connectionId).map { it.repoId }.toSet()

    @Transactional
    override fun replaceSelectedRepos(connectionId: Long, repoIds: Collection<Long>, now: Instant) {
        // set-diff(엔티티 단위) — 벌크 DELETE 로 영속 컨텍스트가 stale 해지는 문제를 피한다.
        val target = repoIds.toSet()
        val existing = connectionRepos.findAllByConnectionId(connectionId)
        val existingIds = existing.map { it.repoId }.toSet()
        existing.filter { it.repoId !in target }.forEach(connectionRepos::delete)
        (target - existingIds).forEach { connectionRepos.save(GithubConnectionRepositoryEntity(connectionId, it, now)) }
    }

    /** 공간 집합 → (ownerUserId 들, workspaceId 들). 빈 쪽은 더미(-1)로 채워 IN () 방언 문제 회피. */
    private fun decompose(spaces: Collection<GithubSpace>): Pair<List<Long>, List<Long>> {
        val ownerIds = spaces.filterIsInstance<GithubSpace.Personal>().map { it.ownerUserId }.ifEmpty { listOf(-1L) }
        val workspaceIds = spaces.filterIsInstance<GithubSpace.Workspace>().map { it.workspaceId }.ifEmpty { listOf(-1L) }
        return ownerIds to workspaceIds
    }
}
