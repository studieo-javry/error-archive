package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionRepositoryStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubConnectionEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubConnectionRepositoryEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubConnectionRepositoryKey

interface GithubConnectionJpaRepository : JpaRepository<GithubConnectionEntity, Long> {

    fun findFirstByInstallationIdAndOwnerUserIdAndStatus(
        installationId: Long,
        ownerUserId: Long,
        status: GithubConnectionStatus,
    ): GithubConnectionEntity?

    fun findFirstByInstallationIdAndWorkspaceIdAndStatus(
        installationId: Long,
        workspaceId: Long,
        status: GithubConnectionStatus,
    ): GithubConnectionEntity?

    // 재연결 대상(DISCONNECTED 누적 가능) — 가장 최근 연결 1건만 결정적으로 고른다(임의행 방지, §5).
    fun findFirstByInstallationIdAndOwnerUserIdOrderByConnectionIdDesc(
        installationId: Long,
        ownerUserId: Long,
    ): GithubConnectionEntity?

    fun findFirstByInstallationIdAndWorkspaceIdOrderByConnectionIdDesc(
        installationId: Long,
        workspaceId: Long,
    ): GithubConnectionEntity?

    fun findAllByInstallationIdAndStatus(
        installationId: Long,
        status: GithubConnectionStatus,
    ): List<GithubConnectionEntity>

    /** 공간들(owner / workspace)의 ACTIVE 연결 전부. 빈 집합은 어댑터에서 더미(-1)로 채워 넘긴다. */
    @Query(
        """
        SELECT c FROM GithubConnectionEntity c
         WHERE c.status = :status
           AND (c.ownerUserId IN :ownerIds OR c.workspaceId IN :workspaceIds)
         ORDER BY c.createdAt
        """,
    )
    fun findActiveBySpaces(
        @Param("status") status: GithubConnectionStatus,
        @Param("ownerIds") ownerIds: Collection<Long>,
        @Param("workspaceIds") workspaceIds: Collection<Long>,
    ): List<GithubConnectionEntity>

    /** 공간(owner 또는 workspace)의 ACTIVE 연결이 repo 를 선택했나. */
    @Query(
        """
        SELECT CASE WHEN COUNT(cr) > 0 THEN true ELSE false END
          FROM GithubConnectionEntity c, GithubConnectionRepositoryEntity cr
         WHERE cr.connectionId = c.connectionId
           AND c.status = :status
           AND cr.status = :repoStatus
           AND cr.repoId = :repoId
           AND ((:ownerUserId IS NOT NULL AND c.ownerUserId = :ownerUserId)
             OR (:workspaceId IS NOT NULL AND c.workspaceId = :workspaceId))
        """,
    )
    fun existsRepoSelectedInSpace(
        @Param("status") status: GithubConnectionStatus,
        @Param("repoStatus") repoStatus: GithubConnectionRepositoryStatus,
        @Param("ownerUserId") ownerUserId: Long?,
        @Param("workspaceId") workspaceId: Long?,
        @Param("repoId") repoId: Long,
    ): Boolean

    /** 주어진 공간들 중 repo 를 선택한 ACTIVE 연결(어댑터가 공간으로 역매핑). */
    @Query(
        """
        SELECT c FROM GithubConnectionEntity c, GithubConnectionRepositoryEntity cr
         WHERE cr.connectionId = c.connectionId
           AND c.status = :status
           AND cr.status = :repoStatus
           AND cr.repoId = :repoId
           AND (c.ownerUserId IN :ownerIds OR c.workspaceId IN :workspaceIds)
        """,
    )
    fun connectionsSelectingRepo(
        @Param("status") status: GithubConnectionStatus,
        @Param("repoStatus") repoStatus: GithubConnectionRepositoryStatus,
        @Param("ownerIds") ownerIds: Collection<Long>,
        @Param("workspaceIds") workspaceIds: Collection<Long>,
        @Param("repoId") repoId: Long,
    ): List<GithubConnectionEntity>
}

interface GithubConnectionRepositoryJpaRepository :
    JpaRepository<GithubConnectionRepositoryEntity, GithubConnectionRepositoryKey> {

    fun findAllByConnectionId(connectionId: Long): List<GithubConnectionRepositoryEntity>

    @Modifying
    @Query("DELETE FROM GithubConnectionRepositoryEntity cr WHERE cr.connectionId = :connectionId")
    fun deleteAllByConnectionId(@Param("connectionId") connectionId: Long): Int

    /** ACTIVE 선택만(ACCESS_LOST 제외). */
    @Query(
        """
        SELECT cr.repoId FROM GithubConnectionRepositoryEntity cr
         WHERE cr.connectionId IN :connectionIds AND cr.status = :repoStatus
        """,
    )
    fun selectedRepoIds(
        @Param("connectionIds") connectionIds: Collection<Long>,
        @Param("repoStatus") repoStatus: GithubConnectionRepositoryStatus,
    ): List<Long>

    /** 이 레포들의 ACTIVE 선택을 ACCESS_LOST 로 전환(이미 ACCESS_LOST 는 그대로). 바뀐 행 수 반환. */
    @Modifying
    @Query(
        """
        UPDATE GithubConnectionRepositoryEntity cr
           SET cr.status = org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionRepositoryStatus.ACCESS_LOST
         WHERE cr.repoId IN :repoIds
           AND cr.status = org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionRepositoryStatus.ACTIVE
        """,
    )
    fun markSelectionsAccessLost(@Param("repoIds") repoIds: Collection<Long>): Int
}
