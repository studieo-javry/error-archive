package org.studieojavry.coreapi.agentcapture.infrastructure.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
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

    fun findByOriginatingConnectionIdAndStatusOrderByUpdatedAtDescIdDesc(
        originatingConnectionId: UUID,
        status: CaptureStatus,
        pageable: Pageable,
    ): List<AgentCaptureEntity>

    /**
     * 소유자의 여러 상태 초안 첫 페이지. 정렬 `updatedAt DESC, id DESC`.
     * ix_agent_capture_owner_status_updated 로 LIMIT 만큼만 스캔(검토 이력 무한 누적 방어).
     */
    @Query(
        "select c from AgentCaptureEntity c " +
            "where c.ownerUserId = :owner and c.status in :statuses " +
            "order by c.updatedAt desc, c.id desc",
    )
    fun firstPageByOwnerAndStatuses(
        @Param("owner") owner: Long,
        @Param("statuses") statuses: Collection<CaptureStatus>,
        pageable: Pageable,
    ): List<AgentCaptureEntity>

    /**
     * 다음 페이지(keyset) — (updatedAt,id) 튜플이 cursor 보다 "작은"(= 더 과거/뒤) 행만. 같은 정렬.
     * cursor 파라미터는 null 이 아니다(널 바인딩 시 Postgres 타입 추론 실패를 피하려 첫 페이지와 분리).
     */
    @Query(
        "select c from AgentCaptureEntity c " +
            "where c.ownerUserId = :owner and c.status in :statuses " +
            "and (c.updatedAt < :cursorUpdatedAt " +
            "     or (c.updatedAt = :cursorUpdatedAt and c.id < :cursorId)) " +
            "order by c.updatedAt desc, c.id desc",
    )
    fun pageAfterByOwnerAndStatuses(
        @Param("owner") owner: Long,
        @Param("statuses") statuses: Collection<CaptureStatus>,
        @Param("cursorUpdatedAt") cursorUpdatedAt: java.time.Instant,
        @Param("cursorId") cursorId: Long,
        pageable: Pageable,
    ): List<AgentCaptureEntity>

    @Query(
        "select c.status, count(c) from AgentCaptureEntity c " +
            "where c.ownerUserId = :owner and c.createdAt >= :since group by c.status",
    )
    fun countByStatusSince(
        @Param("owner") owner: Long,
        @Param("since") since: java.time.Instant,
    ): List<Array<Any>>

    /** 전역 상태별 현재 건수(ARC Prometheus 게이지 — scrape 마다 조회). */
    fun countByStatus(status: CaptureStatus): Long

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

    @Query("select l.captureId from CaptureSessionLinkEntity l where l.sessionId = :sessionId")
    fun captureIdsBySessionId(@Param("sessionId") sessionId: Long): List<Long>
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

    /**
     * 충돌 없는 멱등 삽입 — UNIQUE(connection_id, client_event_id) 충돌 시 DO NOTHING(예외 없음).
     * jsonb 는 text 바인딩 후 CAST, connection_id 는 uuid 로 CAST. id 는 IDENTITY DEFAULT 로 생성.
     * @return 삽입 행 수(1=삽입, 0=충돌).
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO capture_event
              (connection_id, owner_user_id, project_binding_id, client_event_id, client_kind, client_version,
               session_id, stream_id, tool_call_id, event_type, local_sequence, observed_at, received_at,
               redacted_payload, truncated, evidence_refs, payload_hash, capture_id, status)
            VALUES
              (CAST(:connectionId AS uuid), :ownerUserId, :projectBindingId, :clientEventId, :clientKind, :clientVersion,
               :sessionId, :streamId, :toolCallId, :eventType, :localSequence, :observedAt, :receivedAt,
               CAST(:redactedPayload AS jsonb), :truncated, CAST(:evidenceRefs AS jsonb), :payloadHash, :captureId, :status)
            ON CONFLICT (connection_id, client_event_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("connectionId") connectionId: String,
        @Param("ownerUserId") ownerUserId: Long,
        @Param("projectBindingId") projectBindingId: Long?,
        @Param("clientEventId") clientEventId: String,
        @Param("clientKind") clientKind: String?,
        @Param("clientVersion") clientVersion: String?,
        @Param("sessionId") sessionId: String?,
        @Param("streamId") streamId: String?,
        @Param("toolCallId") toolCallId: String?,
        @Param("eventType") eventType: String,
        @Param("localSequence") localSequence: Long,
        @Param("observedAt") observedAt: Instant,
        @Param("receivedAt") receivedAt: Instant,
        @Param("redactedPayload") redactedPayload: String,
        @Param("truncated") truncated: Boolean,
        @Param("evidenceRefs") evidenceRefs: String,
        @Param("payloadHash") payloadHash: String,
        @Param("captureId") captureId: Long?,
        @Param("status") status: String,
    ): Int

    @Query(
        "SELECT e FROM CaptureEventEntity e WHERE e.ownerUserId = :owner AND e.status = :status " +
            "AND (:sessionId IS NULL OR e.sessionId = :sessionId) ORDER BY e.observedAt DESC, e.id DESC",
    )
    fun findByOwnerAndStatus(
        @Param("owner") owner: Long,
        @Param("status") status: CaptureEventStatus,
        @Param("sessionId") sessionId: String?,
        pageable: Pageable,
    ): List<CaptureEventEntity>
}

interface ProjectBindingJpaRepository : JpaRepository<ProjectBindingEntity, Long> {
    fun findByOwnerUserIdAndProjectKey(ownerUserId: Long, projectKey: String): ProjectBindingEntity?
    fun findByOwnerUserIdOrderByProjectKeyAsc(ownerUserId: Long): List<ProjectBindingEntity>

    /**
     * 충돌 없는 멱등 삽입 — UNIQUE(owner_user_id, project_key) 충돌 시 DO NOTHING(예외 없음).
     * id 는 IDENTITY DEFAULT 로 생성. @return 삽입 행 수(1=삽입, 0=충돌).
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO project_binding
              (owner_user_id, project_key, normalized_repo_id, storage_policy, created_at, updated_at)
            VALUES
              (:ownerUserId, :projectKey, :normalizedRepoId, :storagePolicy, :createdAt, :updatedAt)
            ON CONFLICT (owner_user_id, project_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("ownerUserId") ownerUserId: Long,
        @Param("projectKey") projectKey: String,
        @Param("normalizedRepoId") normalizedRepoId: String?,
        @Param("storagePolicy") storagePolicy: String,
        @Param("createdAt") createdAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
