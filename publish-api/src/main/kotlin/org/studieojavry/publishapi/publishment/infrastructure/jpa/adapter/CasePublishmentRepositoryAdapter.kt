package org.studieojavry.publishapi.publishment.infrastructure.jpa.adapter

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.application.port.MyPublishmentQuery
import org.studieojavry.publishapi.publishment.application.port.MyPublishmentSort
import org.studieojavry.publishapi.publishment.application.port.OwnerPublishmentSummary
import org.studieojavry.publishapi.publishment.application.port.PublicPageMeta
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.PublishmentStatus
import org.studieojavry.publishapi.publishment.domain.Visibility
import org.studieojavry.publishapi.publishment.infrastructure.jpa.CasePublishmentEntity
import org.studieojavry.publishapi.publishment.infrastructure.jpa.CasePublishmentJpaRepository
import tools.jackson.databind.ObjectMapper

@Repository
class CasePublishmentRepositoryAdapter(
    private val jpa: CasePublishmentJpaRepository,
    private val objectMapper: ObjectMapper,
) : CasePublishmentRepositoryPort {

    override fun save(p: CasePublishment): CasePublishment {
        val entity = CasePublishmentEntity(
            id = p.id,
            slug = p.slug,
            ownerUserId = p.ownerUserId,
            originalCaseId = p.originalCaseId,
            title = p.title,
            summary = p.summary,
            visibility = p.visibility,
            contentJson = objectMapper.writeValueAsString(p.contentSnapshot),
            optionsJson = objectMapper.writeValueAsString(p.options),
            version = p.version,
            viewCount = p.viewCount,
            downloadCount = p.downloadCount,
            publishedAt = p.publishedAt,
            updatedAt = p.updatedAt,
            sourceState = p.sourceState,
            status = p.status,
        )
        val saved = jpa.save(entity)
        return saved.toDomain()
    }

    override fun update(p: CasePublishment): CasePublishment {
        val existing = jpa.findById(p.id!!).orElseThrow()
        existing.title = p.title
        existing.summary = p.summary
        existing.visibility = p.visibility
        existing.contentJson = objectMapper.writeValueAsString(p.contentSnapshot)
        existing.optionsJson = objectMapper.writeValueAsString(p.options)
        existing.version = p.version
        existing.updatedAt = p.updatedAt
        existing.status = p.status
        return jpa.save(existing).toDomain()
    }

    override fun findBySlug(slug: String): CasePublishment? =
        jpa.findBySlug(slug)?.toDomain()

    override fun findPublicMetaBySlug(slug: String): PublicPageMeta? =
        jpa.findPublicMetaBySlug(slug)?.let {
            PublicPageMeta(status = it.status, ownerUserId = it.ownerUserId, updatedAt = it.updatedAt)
        }

    override fun findByOriginalCaseId(caseId: Long): CasePublishment? =
        jpa.findByOriginalCaseId(caseId)?.toDomain()

    override fun findById(id: Long): CasePublishment? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun deleteById(id: Long) {
        jpa.deleteById(id)
    }

    override fun listByOwner(ownerUserId: Long): List<CasePublishment> =
        jpa.findAllByOwnerUserIdOrderByPublishedAtDesc(ownerUserId).map { it.toDomain() }

    override fun listByVisibility(visibility: Visibility): List<CasePublishment> =
        jpa.findAllByVisibilityOrderByPublishedAtDesc(visibility).map { it.toDomain() }

    override fun listPublicDiscoverable(): List<CasePublishment> =
        jpa.findAllByVisibilityAndStatusOrderByPublishedAtDesc(Visibility.PUBLIC, PublishmentStatus.LIVE)
            .map { it.toDomain() }

    override fun incrementViewCount(slug: String) {
        jpa.incrementViewCount(slug)
    }

    override fun incrementDownloadCount(slug: String) {
        jpa.incrementDownloadCount(slug)
    }

    override fun existsBySlug(slug: String): Boolean = jpa.existsBySlug(slug)

    override fun markSourceDeleted(id: Long) {
        jpa.markSourceDeleted(id)
    }

    override fun pageByOwner(query: MyPublishmentQuery): List<CasePublishment> {
        val pageable = PageRequest.of(0, query.limit)
        val hasCursor = query.cursorValue != null && query.cursorId != null
        val entities = when (query.sort) {
            MyPublishmentSort.VIEWS -> jpa.pageByViews(
                owner = query.ownerUserId, vis = query.visibility, q = query.q,
                hasCursor = hasCursor, cursorViews = query.cursorValue?.toLong(), cursorId = query.cursorId, pageable = pageable,
            )
            MyPublishmentSort.PUBLISHED -> jpa.pageByPublished(
                owner = query.ownerUserId, vis = query.visibility, q = query.q,
                hasCursor = hasCursor, cursorTs = query.cursorValue?.let(java.time.LocalDateTime::parse), cursorId = query.cursorId, pageable = pageable,
            )
        }
        return entities.map { it.toDomain() }
    }

    override fun countByOwner(query: MyPublishmentQuery): Long =
        jpa.countByOwner(owner = query.ownerUserId, vis = query.visibility, q = query.q)

    override fun summaryOf(ownerUserId: Long): OwnerPublishmentSummary {
        var pub = 0L; var unlisted = 0L; var views = 0L; var downloads = 0L
        for (row in jpa.aggregateByOwner(ownerUserId)) {
            val vis = row[0] as Visibility
            val count = (row[1] as Number).toLong()
            views += (row[2] as Number).toLong()
            downloads += (row[3] as Number).toLong()
            when (vis) {
                Visibility.PUBLIC -> pub = count
                Visibility.UNLISTED -> unlisted = count
            }
        }
        return OwnerPublishmentSummary(
            total = pub + unlisted,
            publicCount = pub, unlistedCount = unlisted,
            totalViews = views, totalDownloads = downloads,
        )
    }

    private fun CasePublishmentEntity.toDomain(): CasePublishment = CasePublishment.rehydrate(
        id = id!!, slug = slug, ownerUserId = ownerUserId,
        originalCaseId = originalCaseId,
        title = title, summary = summary,
        visibility = visibility,
        contentSnapshot = objectMapper.readValue(contentJson, ContentSnapshot::class.java),
        options = objectMapper.readValue(optionsJson, PublishOptions::class.java),
        version = version, viewCount = viewCount, downloadCount = downloadCount,
        publishedAt = publishedAt, updatedAt = updatedAt,
        sourceState = sourceState, status = status,
    )
}
