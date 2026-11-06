package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

@Entity
@Table(
    name = "error_case",
    indexes = [
        Index(name = "ix_error_case_workspace", columnList = "meta_workspace_id"),
        Index(name = "ix_error_case_fingerprint", columnList = "snapshot_fingerprint"),
        Index(name = "ix_error_case_created_at", columnList = "created_at"),
        Index(name = "ix_error_case_visibility", columnList = "visibility"),
    ]
)
class ErrorCaseEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(length = 200)
    var project: String? = null,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Embedded
    var snapshot: ErrorSnapshotEmbeddable? = null,

    @Embedded
    var meta: MetaEmbeddable = MetaEmbeddable(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ErrorCaseStatus,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var visibility: Visibility = Visibility.PUBLIC,

    @Column(name = "occurred_at")
    var occurredAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long,

    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,

    @Column(name = "resolved_by_user_id")
    var resolvedByUserId: Long? = null,

    /**
     * 데모 시드 버전 — null 이 아니면 데모 시드 케이스("데모" 배지). 시드 경로만 bulk update(`markDemo`) 로 기록하고,
     * 일반 저장/수정(도메인 → 새 엔티티 merge)은 이 컬럼을 덮어쓰지 않도록 insert/update 에서 제외한다.
     */
    @Column(name = "demo_seed_version", insertable = false, updatable = false)
    var demoSeedVersion: Int? = null,
)
