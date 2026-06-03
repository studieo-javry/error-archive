package org.studieojavry.iamapi.workspace.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationStatus
import java.time.Instant

interface WorkspaceInvitationJpaRepository : JpaRepository<WorkspaceInvitationEntity, Long> {
    fun findByTokenHash(tokenHash: String): WorkspaceInvitationEntity?
    fun findByWorkspaceIdAndStatus(workspaceId: Long, status: InvitationStatus): List<WorkspaceInvitationEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from WorkspaceInvitationEntity i where i.workspaceId = :wid")
    fun deleteAllByWorkspaceId(@Param("wid") workspaceId: Long): Int

    /**
     * 1회용 원자적 claim: PENDING 이고 미만료일 때만 ACCEPTED 로 단일 전이.
     * 영향 행이 1이면 이 호출자가 토큰을 점유(획득)한 것, 0이면 이미 사용/취소/만료 → 거부.
     * 동시 요청은 DB 행 락으로 직렬화되어 정확히 한 번만 1을 받는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        update WorkspaceInvitationEntity i
           set i.status = :accepted, i.acceptedByUserId = :uid, i.acceptedAt = :now
         where i.tokenHash = :hash and i.status = :pending and i.expiresAt > :now
        """
    )
    fun claimForAcceptance(
        @Param("hash") tokenHash: String,
        @Param("uid") acceptingUserId: Long,
        @Param("now") now: Instant,
        @Param("pending") pending: InvitationStatus,
        @Param("accepted") accepted: InvitationStatus
    ): Int
}
