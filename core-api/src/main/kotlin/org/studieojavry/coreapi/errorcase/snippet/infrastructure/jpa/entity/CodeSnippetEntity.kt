package org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "error_case_snippet",
    indexes = [
        Index(name = "ix_snippet_marker", columnList = "marker_id", unique = true),
        Index(name = "ix_snippet_error_case", columnList = "error_case_id"),
        // orphan GC 쿼리(error_case_id IS NULL AND uploaded_at < ?) 전용 (첨부와 동일).
        Index(name = "ix_snippet_orphan_gc", columnList = "error_case_id, uploaded_at")
    ]
)
class CodeSnippetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "error_case_id")
    var errorCaseId: Long? = null,

    @Column(name = "marker_id", length = 32, nullable = false)
    var markerId: String,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(nullable = false, length = 30)
    var language: String,

    @Column(name = "file_path_or_class", length = 500)
    var filePathOrClass: String? = null,

    @Column(name = "line_range", length = 50)
    var lineRange: String? = null,

    @Column(columnDefinition = "TEXT")
    var caption: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    var code: String,

    @Column(name = "uploaded_by_user_id", nullable = false)
    var uploadedByUserId: Long = 0,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: Instant = Instant.EPOCH
)
