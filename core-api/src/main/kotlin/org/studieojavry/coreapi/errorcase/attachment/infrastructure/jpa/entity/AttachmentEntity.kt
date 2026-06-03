package org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import java.time.Instant

@Entity
@Table(
    name = "error_case_attachment",
    indexes = [
        Index(name = "ix_attachment_marker", columnList = "marker_id", unique = true),
        Index(name = "ix_attachment_error_case", columnList = "error_case_id"),
        Index(name = "ix_attachment_uploader_orphan", columnList = "uploaded_by_user_id, error_case_id"),
        // orphan GC 쿼리(error_case_id IS NULL AND uploaded_at < ?) 전용.
        // 운영에선 `WHERE error_case_id IS NULL` 부분 인덱스로 더 최적화 가능(마이그레이션 시).
        Index(name = "ix_attachment_orphan_gc", columnList = "error_case_id, uploaded_at")
    ]
)
class AttachmentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id")
    var errorCaseId: Long? = null,

    @Column(name = "marker_id", length = 32, nullable = false)
    var markerId: String,

    @Column(length = 200)
    var title: String? = null,

    @Column(columnDefinition = "TEXT")
    var caption: String? = null,

    @Column(name = "file_name", nullable = false, length = 500)
    var fileName: String,

    @Column(name = "content_type", nullable = false, length = 200)
    var contentType: String,

    @Column(nullable = false)
    var size: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: AttachmentKind,

    @Column(name = "storage_url", nullable = false, length = 1000)
    var storageUrl: String,

    @Column(name = "preview_text", columnDefinition = "TEXT")
    var previewText: String? = null,

    @Column(name = "uploaded_by_user_id", nullable = false)
    var uploadedByUserId: Long,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: Instant
)
