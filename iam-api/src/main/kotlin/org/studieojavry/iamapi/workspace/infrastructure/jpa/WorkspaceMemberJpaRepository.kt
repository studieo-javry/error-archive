package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole

interface WorkspaceMemberJpaRepository : JpaRepository<WorkspaceMemberEntity, Long> {
    fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMemberEntity?
    fun findByWorkspaceId(workspaceId: Long): List<WorkspaceMemberEntity>
    fun findByUserId(userId: Long): List<WorkspaceMemberEntity>
    fun countByWorkspaceIdAndRole(workspaceId: Long, role: WorkspaceRole): Long

    @Modifying(clearAutomatically = true)
    @Query("delete from WorkspaceMemberEntity m where m.workspaceId = :wid and m.userId = :uid")
    fun deleteRelation(@Param("wid") workspaceId: Long, @Param("uid") userId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("delete from WorkspaceMemberEntity m where m.workspaceId = :wid")
    fun deleteAllByWorkspaceId(@Param("wid") workspaceId: Long): Int
}