package org.studieojavry.coreapi.errorcase.case.domain.model

import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

class ErrorCase private constructor(
    val id: Long?,
    val ownerUserId: Long,
    var title: String,
    var project: String?,
    var snapshot: ErrorSnapshot?,
    var description: String?,
    var meta: Meta,
    var visibility: Visibility,
    val snippets: MutableList<CodeSnippet>,
    val attachments: MutableList<Attachment>,
    /**
     * 자유 태그(예: `k8s`, `java`, `prod`). UI 에서 enter/x 단위로 관리.
     * 정규화: trim·소문자·max 32자·중복 금지·max 20개/케이스(use case 단에서 검증).
     */
    val tags: MutableList<String>,
    var status: ErrorCaseStatus,
    val occurredAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime,
    /** RESOLVED 로 전환된 마지막 시각 — 다른 status 로 다시 바뀌어도 *유지* (timeline 기록용). */
    var resolvedAt: LocalDateTime? = null,
    /** RESOLVED 로 전환한 actor. timeline 의 "Resolved a case" 활동 주체 식별. */
    var resolvedByUserId: Long? = null,
) {
    init {
        require(title.isNotBlank()) { "Title cannot be blank" }
        require(title.length <= 200) { "Title cannot contain more than 200 characters" }
        require(!(visibility == Visibility.WORKSPACE && meta.workspaceId == null)) {
            "Visibility.WORKSPACE requires workspaceId"
        }
    }

    private fun touch() {
        this.updatedAt = LocalDateTime.now()
    }

    fun transitionTo(next: ErrorCaseStatus, actorUserId: Long) {
        if (status == next) return
        val allowed = when (status) {
            ErrorCaseStatus.DRAFT -> setOf(ErrorCaseStatus.OPEN, ErrorCaseStatus.CLOSED)
            ErrorCaseStatus.OPEN -> setOf(ErrorCaseStatus.IN_PROGRESS, ErrorCaseStatus.CLOSED)
            ErrorCaseStatus.IN_PROGRESS -> setOf(ErrorCaseStatus.RESOLVED, ErrorCaseStatus.OPEN, ErrorCaseStatus.CLOSED)
            ErrorCaseStatus.RESOLVED -> setOf(ErrorCaseStatus.IN_PROGRESS, ErrorCaseStatus.CLOSED)
            ErrorCaseStatus.CLOSED -> emptySet()
        }
        require(next in allowed) { "illegal status transition: $status → $next" }
        this.status = next
        if (next == ErrorCaseStatus.RESOLVED) {
            this.resolvedAt = LocalDateTime.now()
            this.resolvedByUserId = actorUserId
        }
        touch()
    }

    fun update(
        title: String,
        project: String?,
        snapshot: ErrorSnapshot?,
        description: String?,
        meta: Meta,
        visibility: Visibility,
    ) {
        require(title.isNotBlank()) { "Title cannot be blank" }
        require(title.length <= 200) { "Title cannot contain more than 200 characters" }
        require(!(visibility == Visibility.WORKSPACE && meta.workspaceId == null)) {
            "Visibility.WORKSPACE requires workspaceId"
        }
        this.title = title
        this.project = project
        this.snapshot = snapshot
        this.description = description
        this.meta = meta
        this.visibility = visibility
        touch()
    }

    companion object {
        const val TAG_MAX_LENGTH = 32
        const val TAGS_MAX_PER_CASE = 20

        /** 태그 정규화: trim · 소문자 · 빈 문자열 거부 · ≤32. null 반환 시 무시. */
        fun normalizeTag(raw: String): String? {
            val v = raw.trim().lowercase()
            if (v.isEmpty()) return null
            require(v.length <= TAG_MAX_LENGTH) { "tag must be $TAG_MAX_LENGTH chars or less: $v" }
            return v
        }

        fun create(
            ownerUserId: Long,
            title: String,
            project: String?,
            snapshot: ErrorSnapshot?,
            description: String?,
            meta: Meta,
            visibility: Visibility = Visibility.PUBLIC,
            snippets: List<CodeSnippet>,
            attachments: List<Attachment>,
            tags: List<String> = emptyList(),
            occurredAt: LocalDateTime?
        ): ErrorCase {
            val now = LocalDateTime.now()
            return ErrorCase(
                id = null,
                ownerUserId = ownerUserId,
                title = title,
                project = project,
                snapshot = snapshot,
                description = description,
                meta = meta,
                visibility = visibility,
                snippets = snippets.toMutableList(),
                attachments = attachments.toMutableList(),
                tags = tags.toMutableList(),
                status = ErrorCaseStatus.OPEN,
                occurredAt = occurredAt,
                createdAt = now,
                updatedAt = now
            )
        }

        fun reconstitute(
            id: Long,
            ownerUserId: Long,
            title: String,
            project: String?,
            snapshot: ErrorSnapshot?,
            description: String?,
            meta: Meta,
            visibility: Visibility,
            snippets: List<CodeSnippet>,
            attachments: List<Attachment>,
            tags: List<String> = emptyList(),
            status: ErrorCaseStatus,
            occurredAt: LocalDateTime?,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
            resolvedAt: LocalDateTime? = null,
            resolvedByUserId: Long? = null,
        ): ErrorCase = ErrorCase(
            id = id,
            ownerUserId = ownerUserId,
            title = title,
            project = project,
            snapshot = snapshot,
            description = description,
            meta = meta,
            visibility = visibility,
            snippets = snippets.toMutableList(),
            attachments = attachments.toMutableList(),
            tags = tags.toMutableList(),
            status = status,
            occurredAt = occurredAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            resolvedAt = resolvedAt,
            resolvedByUserId = resolvedByUserId,
        )
    }
}
