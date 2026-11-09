package org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.studieojavry.coreapi.agentcapture.domain.model.ArtifactStatus
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import java.time.Instant
import java.util.UUID

/**
 * 모든 agent_capture* 테이블 엔티티. 구조화 서사 필드는 직렬화된 JSON 을 jsonb 컬럼 하나에 담는다
 * (@JdbcTypeCode(SqlTypes.JSON) + columnDefinition="jsonb" — outbox payload 와 동일 패턴).
 * 컬럼/제약은 V19 마이그레이션과 정확히 일치해야 한다(Testcontainers ddl-auto=validate).
 */
@Entity
@Table(
    name = "agent_capture",
    uniqueConstraints = [UniqueConstraint(name = "uq_agent_capture_uuid", columnNames = ["capture_uuid"])],
    indexes = [Index(name = "ix_agent_capture_owner_status", columnList = "owner_user_id, status")],
)
class AgentCaptureEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "capture_uuid", nullable = false)
    var captureUuid: UUID,

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long,

    @Column(name = "originating_connection_id", nullable = false)
    var originatingConnectionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CaptureStatus,

    @Version
    @Column(name = "revision", nullable = false)
    var revision: Int = 0,

    @Column(name = "title", length = 300)
    var title: String? = null,

    @Column(name = "project", length = 200)
    var project: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb", nullable = false)
    var content: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "imported_case_id")
    var importedCaseId: Long? = null,
)

@Entity
@Table(
    name = "capture_attempt",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_capture_attempt_client_id", columnNames = ["capture_id", "client_attempt_id"]),
    ],
    indexes = [Index(name = "ix_capture_attempt_capture", columnList = "capture_id")],
)
class CaptureAttemptEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "capture_id", nullable = false)
    var captureId: Long,

    @Column(name = "client_attempt_id", nullable = false, length = 200)
    var clientAttemptId: String,

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    var result: AttemptResult,

    @Column(name = "attempt_type", length = 100)
    var attemptType: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb", nullable = false)
    var detail: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

@Entity
@Table(
    name = "capture_solution_candidate",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_capture_solution_client_id", columnNames = ["capture_id", "client_solution_id"]),
    ],
    indexes = [Index(name = "ix_capture_solution_capture", columnList = "capture_id")],
)
class CaptureSolutionCandidateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "capture_id", nullable = false)
    var captureId: Long,

    @Column(name = "client_solution_id", nullable = false, length = 200)
    var clientSolutionId: String,

    @Column(name = "title", nullable = false, length = 300)
    var title: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb", nullable = false)
    var content: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)

@Entity
@Table(
    name = "capture_session",
    uniqueConstraints = [UniqueConstraint(name = "uq_capture_session_uuid", columnNames = ["session_uuid"])],
)
class CaptureSessionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "session_uuid", nullable = false)
    var sessionUuid: UUID,

    @Column(name = "connection_id", nullable = false)
    var connectionId: UUID,

    @Column(name = "client_kind", nullable = false, length = 50)
    var clientKind: String,

    @Column(name = "client_version", length = 50)
    var clientVersion: String? = null,

    @Column(name = "client_session_id", nullable = false, length = 200)
    var clientSessionId: String,

    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant,
)

@Entity
@Table(
    name = "capture_session_link",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_capture_session_link", columnNames = ["session_id", "capture_id"]),
    ],
    indexes = [Index(name = "ix_capture_session_link_capture", columnList = "capture_id")],
)
class CaptureSessionLinkEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "session_id", nullable = false)
    var sessionId: Long,

    @Column(name = "capture_id", nullable = false)
    var captureId: Long,
)

@Entity
@Table(
    name = "capture_artifact",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_capture_artifact_client_id", columnNames = ["capture_id", "client_artifact_id"]),
    ],
    indexes = [Index(name = "ix_capture_artifact_capture", columnList = "capture_id")],
)
class CaptureArtifactEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "capture_id", nullable = false)
    var captureId: Long,

    @Column(name = "client_artifact_id", nullable = false, length = 200)
    var clientArtifactId: String,

    @Column(name = "object_key", nullable = false, length = 300)
    var objectKey: String,

    @Column(name = "sha256", nullable = false, length = 64)
    var sha256: String,

    @Column(name = "content_type", nullable = false, length = 100)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    @Column(name = "file_name", length = 300)
    var fileName: String? = null,

    @Column(name = "uploader_user_id", nullable = false)
    var uploaderUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ArtifactStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)

@Entity
@Table(
    name = "agent_idempotency_receipt",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_agent_idempotency",
            columnNames = ["scope_key", "operation", "idempotency_key"],
        ),
    ],
)
class IdempotencyReceiptEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "scope_key", nullable = false, length = 200)
    var scopeKey: String,

    @Column(name = "operation", nullable = false, length = 60)
    var operation: String,

    @Column(name = "idempotency_key", nullable = false, length = 200)
    var idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String,

    @Column(name = "response_status", nullable = false)
    var responseStatus: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb", nullable = false)
    var responseBody: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)
