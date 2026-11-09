package org.studieojavry.coreapi.agentcapture.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.AgentCaptureEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureArtifactEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureAttemptEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionLinkEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSolutionCandidateEntity
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.IdempotencyReceiptEntity
import java.util.UUID

interface AgentCaptureJpaRepository : JpaRepository<AgentCaptureEntity, Long> {
    fun findByCaptureUuid(captureUuid: UUID): AgentCaptureEntity?
    fun findByCaptureUuidAndOwnerUserId(captureUuid: UUID, ownerUserId: Long): AgentCaptureEntity?
    fun findByOwnerUserIdAndStatusOrderByUpdatedAtDescIdDesc(
        ownerUserId: Long,
        status: CaptureStatus,
    ): List<AgentCaptureEntity>
}

interface CaptureAttemptJpaRepository : JpaRepository<CaptureAttemptEntity, Long> {
    fun findByCaptureIdAndClientAttemptId(captureId: Long, clientAttemptId: String): CaptureAttemptEntity?
    fun findByCaptureIdOrderByOrderIndexAscIdAsc(captureId: Long): List<CaptureAttemptEntity>
    fun countByCaptureId(captureId: Long): Long

    @Query("select a.clientAttemptId from CaptureAttemptEntity a where a.captureId = :captureId")
    fun clientAttemptIds(@Param("captureId") captureId: Long): List<String>
}

interface CaptureSolutionJpaRepository : JpaRepository<CaptureSolutionCandidateEntity, Long> {
    fun findByCaptureIdAndClientSolutionId(captureId: Long, clientSolutionId: String): CaptureSolutionCandidateEntity?
    fun findByCaptureIdOrderByIdAsc(captureId: Long): List<CaptureSolutionCandidateEntity>
}

interface CaptureArtifactJpaRepository : JpaRepository<CaptureArtifactEntity, Long> {
    fun findByCaptureIdAndClientArtifactId(captureId: Long, clientArtifactId: String): CaptureArtifactEntity?
    fun findByCaptureIdOrderByIdAsc(captureId: Long): List<CaptureArtifactEntity>
    fun countByCaptureId(captureId: Long): Long

    @Query("select coalesce(sum(a.sizeBytes), 0) from CaptureArtifactEntity a where a.captureId = :captureId")
    fun totalSizeByCaptureId(@Param("captureId") captureId: Long): Long

    @Query("select a.clientArtifactId from CaptureArtifactEntity a where a.captureId = :captureId and a.status in ('COMPLETED','LINKED')")
    fun completedClientIds(@Param("captureId") captureId: Long): List<String>
}

interface CaptureSessionJpaRepository : JpaRepository<CaptureSessionEntity, Long> {
    fun findBySessionUuid(sessionUuid: UUID): CaptureSessionEntity?
}

interface CaptureSessionLinkJpaRepository : JpaRepository<CaptureSessionLinkEntity, Long> {
    fun existsBySessionIdAndCaptureId(sessionId: Long, captureId: Long): Boolean
}

interface IdempotencyReceiptJpaRepository : JpaRepository<IdempotencyReceiptEntity, Long> {
    fun findByScopeKeyAndOperationAndIdempotencyKey(
        scopeKey: String,
        operation: String,
        idempotencyKey: String,
    ): IdempotencyReceiptEntity?
}
