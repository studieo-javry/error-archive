package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.port.SortBy
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseTagEntity
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

    override fun findRecentByOwner(ownerUserId: Long, limit: Int): List<ErrorCaseSummary> {
        val entities = errorCaseRepo.findAllByOwnerUserIdOrderByUpdatedAtDescIdDesc(
            ownerUserId, PageRequest.of(0, limit)
        )
        return hydrateSummaries(entities)
    }

    override fun findSummariesByIds(caseIds: Collection<Long>): List<ErrorCaseSummary> {
        if (caseIds.isEmpty()) return emptyList()
        val entities = errorCaseRepo.findAllByIdIn(caseIds)
        return hydrateSummaries(entities)
    }

    override fun findRecentlyResolvedByActor(
        actorUserId: Long,
        since: LocalDateTime,
        limit: Int,
    ): List<ErrorCaseSummary> {
        val entities = errorCaseRepo
            .findAllByResolvedByUserIdAndResolvedAtGreaterThanEqualOrderByResolvedAtDescIdDesc(
                actorUserId, since, PageRequest.of(0, limit),
            )
        return hydrateSummaries(entities)
    }

    override fun countCreatedByOwnerSince(ownerUserId: Long, since: LocalDateTime): Long =
        errorCaseRepo.countByOwnerUserIdAndCreatedAtGreaterThanEqual(ownerUserId, since)

    override fun countResolvedByActorSince(actorUserId: Long, since: LocalDateTime): Long =
        errorCaseRepo.countByResolvedByUserIdAndResolvedAtGreaterThanEqual(actorUserId, since)

    override fun findRecentPublicByOwners(
        ownerUserIds: Collection<Long>,
        limit: Int,
    ): List<ErrorCaseSummary> {
        if (ownerUserIds.isEmpty()) return emptyList()
        val entities = errorCaseRepo
            .findAllByOwnerUserIdInAndVisibilityOrderByCreatedAtDescIdDesc(
                ownerUserIds, Visibility.PUBLIC, PageRequest.of(0, limit),
            )
        return hydrateSummaries(entities)
    }

    override fun findTopOwnersOfRecentPublic(since: LocalDateTime, limit: Int): List<Long> {
        return errorCaseRepo.findTopOwnersOfRecentPublic(since, PageRequest.of(0, limit))
            .map { it.userId }
    }

    override fun findRandomPublicCaseOwners(limit: Int): List<Long> {
        if (limit <= 0) return emptyList()
        return errorCaseRepo.findRandomPublicCaseOwners(limit)
    }

    private fun hydrateSummaries(entities: List<ErrorCaseEntity>): List<ErrorCaseSummary> {
        val ids = entities.mapNotNull { it.id }
        val tagsByCaseId: Map<Long, List<String>> = if (ids.isEmpty()) emptyMap() else
            tagRepo.findAllByErrorCaseIdInOrderByCreatedAtAscIdAsc(ids)
                .groupBy { it.errorCaseId }
                .mapValues { (_, rows) -> rows.map { it.tag } }
        return entities.map { it.toSummary(tagsByCaseId[it.id] ?: emptyList()) }
    }

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
        criteria.visibility?.let { preds += cb.equal(root.get<Visibility>("visibility"), it) }
        criteria.fingerprint?.let {
            preds += cb.equal(root.get<ErrorSnapshotEmbeddable>("snapshot").get<String>("fingerprint"), it)
        }
        // q: title ILIKE %q% OR EXISTS tag t WHERE t.error_case_id = c.id AND t.tag ILIKE %q%
        criteria.q?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
            val pattern = "%${q.lowercase()}%"
            val titleMatch = cb.like(cb.lower(root.get("title")), pattern)
            val tagSub = cq.subquery(Long::class.java)
            val tagRoot = tagSub.from(ErrorCaseTagEntity::class.java)
            tagSub.select(tagRoot.get("errorCaseId"))
                .where(
                    cb.equal(tagRoot.get<Long>("errorCaseId"), root.get<Long>("id")),
                    cb.like(cb.lower(tagRoot.get("tag")), pattern),
                )
            val tagMatch = cb.exists(tagSub)
            preds += cb.or(titleMatch, tagMatch)
        }

        // Cursor: keyset — sortBy 필드 기반. (sortAt < c) OR (sortAt = c AND id < cId).
        val sortField = when (criteria.sortBy) {
            SortBy.CREATED -> "createdAt"
            SortBy.UPDATED -> "updatedAt"
        }
        if (criteria.cursorCreatedAt != null && criteria.cursorId != null) {
            val sortAt = root.get<LocalDateTime>(sortField)
            val idPath = root.get<Long>("id")
            preds += cb.or(
                cb.lessThan(sortAt, criteria.cursorCreatedAt),
                cb.and(cb.equal(sortAt, criteria.cursorCreatedAt), cb.lessThan(idPath, criteria.cursorId))
            )
        }

        cq.select(root)
            .where(*preds.toTypedArray())
            .orderBy(cb.desc(root.get<LocalDateTime>(sortField)), cb.desc(root.get<Long>("id")))

        val entities = em.createQuery(cq)
            .setMaxResults(criteria.limit)
            .resultList

        // 태그 N+1 회피 — IN (ids) 단일 쿼리 후 in-memory 그룹핑
        val ids = entities.mapNotNull { it.id }
        val tagsByCaseId: Map<Long, List<String>> = if (ids.isEmpty()) emptyMap() else
            tagRepo.findAllByErrorCaseIdInOrderByCreatedAtAscIdAsc(ids)
                .groupBy { it.errorCaseId }
                .mapValues { (_, rows) -> rows.map { it.tag } }

        return entities.map { it.toSummary(tagsByCaseId[it.id] ?: emptyList()) }
    }

    /**
     * search 필터 (workspaceId / ownerUserId / q / visibility) 를 그대로 적용,
     * status 는 무시하고 status 별 개수. Library 페이지의 status filter chip 카운트용.
     * cursor / sortBy 는 무의미하므로 무시.
     */
    override fun countByStatus(
        criteria: ErrorCaseSearchCriteria
    ): Map<ErrorCaseStatus, Long> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Array<Any>::class.java)
        val root = cq.from(ErrorCaseEntity::class.java)
        val preds = mutableListOf<Predicate>()

        criteria.workspaceId?.let { preds += cb.equal(root.get<MetaEmbeddable>("meta").get<Long>("workspaceId"), it) }
        criteria.ownerUserId?.let { preds += cb.equal(root.get<Long>("ownerUserId"), it) }
        criteria.visibility?.let { preds += cb.equal(root.get<Visibility>("visibility"), it) }
        criteria.fingerprint?.let {
            preds += cb.equal(root.get<ErrorSnapshotEmbeddable>("snapshot").get<String>("fingerprint"), it)
        }
        criteria.q?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
            val pattern = "%${q.lowercase()}%"
            val titleMatch = cb.like(cb.lower(root.get("title")), pattern)
            val tagSub = cq.subquery(Long::class.java)
            val tagRoot = tagSub.from(ErrorCaseTagEntity::class.java)
            tagSub.select(tagRoot.get("errorCaseId"))
                .where(
                    cb.equal(tagRoot.get<Long>("errorCaseId"), root.get<Long>("id")),
                    cb.like(cb.lower(tagRoot.get("tag")), pattern),
                )
            preds += cb.or(titleMatch, cb.exists(tagSub))
        }

        val statusPath = root.get<ErrorCaseStatus>("status")
        cq.multiselect(statusPath, cb.count(root))
            .where(*preds.toTypedArray())
            .groupBy(statusPath)

        val rows = em.createQuery(cq).resultList
        return rows.associate { row ->
            @Suppress("UNCHECKED_CAST")
            val status = row[0] as ErrorCaseStatus
            val count = row[1] as Long
            status to count
        }
    }

    private fun ErrorCaseEntity.toSummary(tags: List<String>): ErrorCaseSummary {
        // 모든 meta 컬럼이 null 이면 Hibernate 가 embedded 를 null 로 돌려준다(개인 케이스 등).
        val m: MetaEmbeddable? = meta
        return ErrorCaseSummary(
            id = id!!,
            ownerUserId = ownerUserId,
            title = title,
            status = status,
            visibility = visibility,
            workspaceId = m?.workspaceId,
            fingerprint = snapshot?.fingerprint,
            exceptionClass = snapshot?.exceptionClass,
            createdAt = createdAt,
            updatedAt = updatedAt,
            occurredAt = occurredAt,
            tags = tags,
            descriptionRaw = description,
            resolvedAt = resolvedAt,
            resolvedByUserId = resolvedByUserId,
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
        ownerUserId = ownerUserId,
        resolvedAt = resolvedAt,
        resolvedByUserId = resolvedByUserId,
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
            updatedAt = updatedAt,
            resolvedAt = resolvedAt,
            resolvedByUserId = resolvedByUserId,
        )
    }
}
