package org.studieojavry.coreapi.errorcase.eventevidence.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * case_event_evidence(V30). 수집 근거 Case 스냅샷. redacted_payload 는 jsonb(@JdbcTypeCode JSON + String,
 * capture_event 와 동일 패턴). FK 없음(error_case_attachment 가문) — 삭제 정리는 애플리케이션 cascade.
 */
@Entity
@Table(
    name = "case_event_evidence",
    indexes = [
        Index(name = "ix_case_event_evidence_case", columnList = "error_case_id"),
    ],
)
class CaseEventEvidenceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "source_capture_event_id", nullable = false)
    var sourceCaptureEventId: Long,

    @Column(name = "source_client_event_id", nullable = false, length = 200)
    var sourceClientEventId: String,

    @Column(name = "event_type", nullable = false, length = 60)
    var eventType: String,

    @Column(name = "observed_at", nullable = false)
    var observedAt: Instant,

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_payload", columnDefinition = "jsonb", nullable = false)
    var redactedPayload: String,

    @Column(nullable = false)
    var truncated: Boolean,

    @Column(name = "reviewed_by_user_id", nullable = false)
    var reviewedByUserId: Long,

    @Column(name = "reviewed_at", nullable = false)
    var reviewedAt: Instant,

    @Column(name = "visibility_policy", nullable = false, length = 20)
    var visibilityPolicy: String,
)
