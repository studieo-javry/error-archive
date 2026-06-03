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
    var scope: String?,
    var snapshot: ErrorSnapshot?,
    var description: String?,
    var meta: Meta,
    var visibility: Visibility,
    val snippets: MutableList<CodeSnippet>,
    val attachments: MutableList<Attachment>,
    var status: ErrorCaseStatus,
    val occurredAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime
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

    /**
     * 상태 전이. 도메인이 허용하는 전이만:
     *  - OPEN → IN_PROGRESS         (첫 step 등록 시 자동)
     *  - IN_PROGRESS → RESOLVED    (사용자 확정)
     *  - RESOLVED → IN_PROGRESS    (재오픈)
     *  - * → CLOSED                 (운영자 / 향후)
     *
     * 같은 상태로 호출하면 no-op(멱등).
     */
    fun transitionTo(next: ErrorCaseStatus) {
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
        touch()
    }

    /** 메타데이터/본문 부분 수정. 스니펫·첨부 연결은 별도 경로(markerId)로 관리하므로 여기서 다루지 않는다. */
    fun update(
        title: String,
        scope: String?,
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
        this.scope = scope
        this.snapshot = snapshot
        this.description = description
        this.meta = meta
        this.visibility = visibility
        touch()
    }

    companion object {
        fun create(
            ownerUserId: Long,
            title: String,
            scope: String?,
            snapshot: ErrorSnapshot?,
            description: String?,
            meta: Meta,
            visibility: Visibility = Visibility.PUBLIC,
            snippets: List<CodeSnippet>,
            attachments: List<Attachment>,
            occurredAt: LocalDateTime?
        ): ErrorCase {
            val now = LocalDateTime.now()
            return ErrorCase(
                id = null,
                ownerUserId = ownerUserId,
                title = title,
                scope = scope,
                snapshot = snapshot,
                description = description,
                meta = meta,
                visibility = visibility,
                snippets = snippets.toMutableList(),
                attachments = attachments.toMutableList(),
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
            scope: String?,
            snapshot: ErrorSnapshot?,
            description: String?,
            meta: Meta,
            visibility: Visibility,
            snippets: List<CodeSnippet>,
            attachments: List<Attachment>,
            status: ErrorCaseStatus,
            occurredAt: LocalDateTime?,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): ErrorCase = ErrorCase(
            id = id,
            ownerUserId = ownerUserId,
            title = title,
            scope = scope,
            snapshot = snapshot,
            description = description,
            meta = meta,
            visibility = visibility,
            snippets = snippets.toMutableList(),
            attachments = attachments.toMutableList(),
            status = status,
            occurredAt = occurredAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
