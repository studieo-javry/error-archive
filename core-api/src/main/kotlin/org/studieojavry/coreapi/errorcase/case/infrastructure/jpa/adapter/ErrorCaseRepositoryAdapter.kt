package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import java.time.LocalDateTime
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Fingerprint
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.AttachmentJpaRepository
import org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.CodeSnippetJpaRepository
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.ErrorCaseJpaRepository
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.entity.AttachmentEntity
import org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.entity.CodeSnippetEntity
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseEntity
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorSnapshotEmbeddable
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.MetaEmbeddable

@Component
class ErrorCaseRepositoryAdapter(
    private val errorCaseRepo: ErrorCaseJpaRepository,
    private val snippetRepo: CodeSnippetJpaRepository,
    private val attachmentRepo: AttachmentJpaRepository,
    private val tagRepo: org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.ErrorCaseTagJpaRepository,
    private val em: EntityManager
) : ErrorCaseRepositoryPort {

    override fun findOwnerUserIdById(errorCaseId: Long): Long? =
        errorCaseRepo.findOwnerUserIdById(errorCaseId)

    override fun deleteById(errorCaseId: Long) = errorCaseRepo.deleteById(errorCaseId)

    override fun findById(errorCaseId: Long): ErrorCase? {
        val entity = errorCaseRepo.findById(errorCaseId).orElse(null) ?: return null
        val snippets = snippetRepo.findAllByErrorCaseId(errorCaseId).map { it.toDomain() }
        val attachments = attachmentRepo.findAllByErrorCaseId(errorCaseId).map { it.toDomain() }
        val tags = tagRepo.findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId).map { it.tag }
        return entity.toDomain(snippets, attachments, tags)
    }

    /** 코어 엔티티만 갱신(id 존재 → update). 스니펫·첨부 연결은 그대로 둔다. */
    override fun update(errorCase: ErrorCase): ErrorCase {
        errorCaseRepo.save(errorCase.toEntity())
        return errorCase
    }

    /**
     * 동적 필터 + keyset 커서 목록 조회. Criteria API 로 **필터가 있을 때만 predicate 추가** —
     * `:param IS NULL` + null 바인드의 Postgres 타입 추론 문제를 회피한다.
     */
    override fun search(criteria: ErrorCaseSearchCriteria): List<ErrorCaseSummary> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(ErrorCaseEntity::class.java)
        val root = cq.from(ErrorCaseEntity::class.java)
        val preds = mutableListOf<Predicate>()

        criteria.workspaceId?.let { preds += cb.equal(root.get<MetaEmbeddable>("meta").get<Long>("workspaceId"), it) }
        criteria.ownerUserId?.let { preds += cb.equal(root.get<Long>("ownerUserId"), it) }
        criteria.status?.let { preds += cb.equal(root.get<ErrorCaseStatus>("status"), it) }
        criteria.severityCode?.let { preds += cb.equal(root.get<MetaEmbeddable>("meta").get<Int>("severityCode"), it) }
        criteria.fingerprint?.let {
            preds += cb.equal(root.get<ErrorSnapshotEmbeddable>("snapshot").get<String>("fingerprint"), it)
        }
        // keyset: (createdAt < c) OR (createdAt = c AND id < cId)
        if (criteria.cursorCreatedAt != null && criteria.cursorId != null) {
            val createdAt = root.get<LocalDateTime>("createdAt")
            val idPath = root.get<Long>("id")
            preds += cb.or(
                cb.lessThan(createdAt, criteria.cursorCreatedAt),
                cb.and(cb.equal(createdAt, criteria.cursorCreatedAt), cb.lessThan(idPath, criteria.cursorId))
            )
        }

        cq.select(root)
            .where(*preds.toTypedArray())
            .orderBy(cb.desc(root.get<LocalDateTime>("createdAt")), cb.desc(root.get<Long>("id")))

        return em.createQuery(cq)
            .setMaxResults(criteria.limit)
            .resultList
            .map { it.toSummary() }
    }

    private fun ErrorCaseEntity.toSummary(): ErrorCaseSummary {
        // 모든 meta 컬럼이 null 이면 Hibernate 가 embedded 를 null 로 돌려준다(개인 케이스 등).
        val m: MetaEmbeddable? = meta
        return ErrorCaseSummary(
            id = id!!,
            ownerUserId = ownerUserId,
            title = title,
            status = status,
            visibility = visibility,
            severityCode = m?.severityCode,
            workspaceId = m?.workspaceId,
            fingerprint = snapshot?.fingerprint,
            exceptionClass = snapshot?.exceptionClass,
            createdAt = createdAt,
            occurredAt = occurredAt
        )
    }

    override fun save(errorCase: ErrorCase): ErrorCase {
        val saved = errorCaseRepo.save(errorCase.toEntity())

        // 스니펫·첨부 모두 이미 별도로 생성되어 marker 로 존재. errorCaseId 만 연결한다.
        errorCase.snippets.forEach { sn ->
            val entity = snippetRepo.findByMarkerId(sn.markerId)
                ?: error("snippet not found by marker: ${sn.markerId}")
            entity.errorCaseId = saved.id
            snippetRepo.save(entity)
        }
        errorCase.attachments.forEach { att ->
            val entity = attachmentRepo.findByMarkerId(att.markerId)
                ?: error("attachment not found by marker: ${att.markerId}")
            entity.errorCaseId = saved.id
            attachmentRepo.save(entity)
        }

        return saved.toDomain(
            snippets = errorCase.snippets,
            attachments = errorCase.attachments
        )
    }

    private fun ErrorCase.toEntity(): ErrorCaseEntity = ErrorCaseEntity(
        id = id,
        title = title,
        project = project,
        description = description,
        snapshot = snapshot?.toEmbeddable(),
        meta = MetaEmbeddable(
            workspaceId = meta.workspaceId,
            severityCode = meta.severity?.code,
        ),
        status = status,
        visibility = visibility,
        occurredAt = occurredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        ownerUserId = ownerUserId
    )

    private fun ErrorSnapshot.toEmbeddable(): ErrorSnapshotEmbeddable = ErrorSnapshotEmbeddable(
        rawPaste = rawPaste,
        exceptionClass = exceptionClass,
        exceptionMessage = exceptionMessage,
        rawStackTrace = rawStackTrace?.value,
        fingerprint = fingeprint?.value
    )

    private fun CodeSnippetEntity.toDomain(): CodeSnippet = CodeSnippet(
        markerId = markerId,
        title = title,
        language = language,
        filePathOrClass = filePathOrClass,
        lineRange = lineRange,
        caption = caption,
        code = code,
        uploadedByUserId = uploadedByUserId,
        uploadedAt = uploadedAt,
        errorCaseId = errorCaseId
    )

    private fun AttachmentEntity.toDomain(): Attachment = Attachment(
        markerId = markerId,
        title = title,
        caption = caption,
        fileName = fileName,
        contentType = contentType,
        size = size,
        kind = kind,
        storageUrl = storageUrl,
        previewText = previewText,
        uploadedByUserId = uploadedByUserId,
        uploadedAt = uploadedAt,
        errorCaseId = errorCaseId
    )

    private fun ErrorSnapshotEmbeddable.toDomain(): ErrorSnapshot = ErrorSnapshot(
        rawPaste = rawPaste,
        exceptionClass = exceptionClass,
        exceptionMessage = exceptionMessage,
        rawStackTrace = rawStackTrace?.takeIf { it.isNotBlank() }?.let { RawStackTrace(it) },
        fingeprint = fingerprint?.takeIf { it.isNotBlank() }?.let { Fingerprint(it) }
    )

    private fun ErrorCaseEntity.toDomain(
        snippets: List<CodeSnippet>,
        attachments: List<Attachment>,
        tags: List<String> = emptyList(),
    ): ErrorCase {
        val m: MetaEmbeddable? = meta // 전 컬럼 null 이면 embedded 가 null 일 수 있음
        return ErrorCase.reconstitute(
            id = id!!,
            ownerUserId = ownerUserId,
            title = title,
            project = project,
            snapshot = snapshot?.toDomain(),
            description = description,
            meta = Meta(
                workspaceId = m?.workspaceId,
                severity = m?.severityCode?.let { Severity.fromCode(it) },
            ),
            visibility = visibility,
            snippets = snippets,
            attachments = attachments,
            tags = tags,
            status = status,
            occurredAt = occurredAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
