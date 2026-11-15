package org.studieojavry.coreapi.agentcapture.infrastructure.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.AgentCaptureEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureArtifactEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureAttemptEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionLinkEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSolutionCandidateEntity
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureEventEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureImportEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.IdempotencyReceiptEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.ProjectBindingEntity
import java.util.UUID

interface AgentCaptureJpaRepository : JpaRepository<AgentCaptureEntity, Long> {
    fun findByCaptureUuid(captureUuid: UUID): AgentCaptureEntity?
    fun findByCaptureUuidAndOwnerUserId(captureUuid: UUID, ownerUserId: Long): AgentCaptureEntity?
    fun findByOwnerUserIdAndStatusOrderByUpdatedAtDescIdDesc(
        ownerUserId: Long,
        status: CaptureStatus,
    ): List<AgentCaptureEntity>

    /**
     * 변이(쓰기·봉인) 연산 전용 — capture 행을 PESSIMISTIC_WRITE(SELECT … FOR UPDATE)로 잠가
     * complete 와 add-attempt/solution/edit 를 capture 단위로 직렬화한다. add-attempt 는 capture
     * 행을 수정하지 않아 @Version 으로는 complete 와 경합하지 않으므로(봉인되는 capture 에 자식이
     * 끼어들 수 있음), 행 락으로 "봉인 커밋 후엔 어떤 쓰기도 OPEN 을 보지 못함"을 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentCaptureEntity c where c.captureUuid = :uuid and c.ownerUserId = :owner")
    fun findForUpdateByCaptureUuidAndOwnerUserId(
        @Param("uuid") uuid: UUID,
        @Param("owner") owner: Long,
    ): AgentCaptureEntity?
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

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from IdempotencyReceiptEntity r where r.createdAt < :threshold")
    fun deleteByCreatedAtBefore(@Param("threshold") threshold: java.time.Instant): Int
}

interface CaptureImportJpaRepository : JpaRepository<CaptureImportEntity, Long> {
    fun findByCaptureId(captureId: Long): CaptureImportEntity?
}

interface CaptureEventJpaRepository : JpaRepository<CaptureEventEntity, Long> {
    fun findByConnectionIdAndClientEventId(connectionId: UUID, clientEventId: String): CaptureEventEntity?
}

interface ProjectBindingJpaRepository : JpaRepository<ProjectBindingEntity, Long> {
    fun findByOwnerUserIdAndProjectKey(ownerUserId: Long, projectKey: String): ProjectBindingEntity?
    fun findByOwnerUserIdOrderByProjectKeyAsc(ownerUserId: Long): List<ProjectBindingEntity>
}
