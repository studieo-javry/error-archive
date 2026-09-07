package org.studieojavry.publishapi.publishment.infrastructure.jpa

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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.studieojavry.publishapi.publishment.domain.PublishmentStatus
import org.studieojavry.publishapi.publishment.domain.SourceState
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

@Entity
@Table(
    name = "case_publishment",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_case_publishment_slug", columnNames = ["slug"]),
        // 케이스당 1 canonical 발행물 — 원본 case 하나에 발행물 하나만.
        UniqueConstraint(name = "uq_case_publishment_origin", columnNames = ["original_case_id"]),
    ],
    indexes = [
        Index(name = "ix_case_publishment_owner", columnList = "owner_user_id"),
        Index(name = "ix_case_publishment_visibility_published", columnList = "visibility, published_at DESC"),
    ]
)
class CasePublishmentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "slug", nullable = false, length = 32)
    var slug: String,

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long,

    @Column(name = "original_case_id", nullable = false)
    var originalCaseId: Long,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Column(name = "summary", length = 500)
    var summary: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    var visibility: Visibility,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    var contentJson: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", nullable = false, columnDefinition = "jsonb")
    var optionsJson: String,

    @Column(name = "version", nullable = false)
    var version: Int,

    // counter 컬럼은 오직 native `x = x+1` 증가 쿼리로만 변경 — 엔티티 update() 가 전체 컬럼 UPDATE 로
    // 되쓰며 증가분을 덮어쓰는 lost-update 를 막기 위해 updatable=false. (INSERT 는 영향 없음 → create 시 0 저장.)
    @Column(name = "view_count", nullable = false, updatable = false)
    var viewCount: Long,

    @Column(name = "download_count", nullable = false, updatable = false)
    var downloadCount: Long = 0,

    @Column(name = "published_at", nullable = false, updatable = false)
    var publishedAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false, length = 16)
    var sourceState: SourceState = SourceState.LIVE,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: PublishmentStatus = PublishmentStatus.LIVE,
)
