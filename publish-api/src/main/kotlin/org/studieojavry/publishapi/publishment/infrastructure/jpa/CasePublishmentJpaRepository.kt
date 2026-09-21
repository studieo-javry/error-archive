package org.studieojavry.publishapi.publishment.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.publishapi.publishment.domain.PublishmentStatus
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

interface CasePublishmentJpaRepository : JpaRepository<CasePublishmentEntity, Long> {

    fun findBySlug(slug: String): CasePublishmentEntity?

    fun existsBySlug(slug: String): Boolean

    /**
     * 공개 페이지 게이팅 + 렌더 캐시 key 용 **경량 조회** — content_json/options_json(jsonb) 을
     * 읽지 않아 역직렬화 비용이 없다. 캐시 HIT 시 전체 엔티티 로드를 피하기 위한 것.
     */
    @Query(
        "select p.status as status, p.ownerUserId as ownerUserId, p.updatedAt as updatedAt " +
            "from CasePublishmentEntity p where p.slug = :slug",
    )
    fun findPublicMetaBySlug(@Param("slug") slug: String): PublicPageMetaView?

    /** 케이스당 1 canonical — 원본 case 로 발행물 조회(UNIQUE(original_case_id) 전제). */
    fun findByOriginalCaseId(originalCaseId: Long): CasePublishmentEntity?

    @Query("""
        select p from CasePublishmentEntity p
        where p.ownerUserId = :ownerUserId
        order by p.publishedAt desc, p.id desc
    """)
    fun findAllByOwnerUserIdOrderByPublishedAtDesc(@Param("ownerUserId") ownerUserId: Long): List<CasePublishmentEntity>

    @Query("""
        select p from CasePublishmentEntity p
        where p.visibility = :vis
        order by p.publishedAt desc, p.id desc
    """)
    fun findAllByVisibilityOrderByPublishedAtDesc(@Param("vis") visibility: Visibility): List<CasePublishmentEntity>

    /** discovery(sitemap/공개 목록) — visibility + status 동시 필터. UNPUBLISHED 는 자동 제외. */
    @Query("""
        select p from CasePublishmentEntity p
        where p.visibility = :vis and p.status = :status
        order by p.publishedAt desc, p.id desc
    """)
    fun findAllByVisibilityAndStatusOrderByPublishedAtDesc(
        @Param("vis") visibility: Visibility,
        @Param("status") status: PublishmentStatus,
    ): List<CasePublishmentEntity>

    @Modifying
    @Query("update CasePublishmentEntity p set p.viewCount = p.viewCount + 1 where p.slug = :slug")
    fun incrementViewCount(@Param("slug") slug: String): Int

    @Modifying
    @Query("update CasePublishmentEntity p set p.downloadCount = p.downloadCount + 1 where p.slug = :slug")
    fun incrementDownloadCount(@Param("slug") slug: String): Int

    @Modifying
    @Query("update CasePublishmentEntity p set p.sourceState = org.studieojavry.publishapi.publishment.domain.SourceState.SOURCE_DELETED where p.id = :id")
    fun markSourceDeleted(@Param("id") id: Long): Int

    // ──────────────────── 내 발행물 목록 (keyset 페이징) ────────────────────
    // 필터(vis/q)는 nullable 파라미터로 조건부. keyset: (정렬키, id) < (cursor).

    @Query(
        """
        select p from CasePublishmentEntity p
        where p.ownerUserId = :owner
          and (:vis is null or p.visibility = :vis)
          and (:q is null or lower(p.title) like lower(concat('%', cast(:q as string), '%')))
          and (:hasCursor = false or p.publishedAt < :cursorTs
               or (p.publishedAt = :cursorTs and p.id < :cursorId))
        order by p.publishedAt desc, p.id desc
        """
    )
    fun pageByPublished(
        @Param("owner") owner: Long,
        @Param("vis") vis: Visibility?,
        @Param("q") q: String?,
        @Param("hasCursor") hasCursor: Boolean,
        @Param("cursorTs") cursorTs: LocalDateTime?,
        @Param("cursorId") cursorId: Long?,
        pageable: Pageable,
    ): List<CasePublishmentEntity>

    @Query(
        """
        select p from CasePublishmentEntity p
        where p.ownerUserId = :owner
          and (:vis is null or p.visibility = :vis)
          and (:q is null or lower(p.title) like lower(concat('%', cast(:q as string), '%')))
          and (:hasCursor = false or p.viewCount < :cursorViews
               or (p.viewCount = :cursorViews and p.id < :cursorId))
        order by p.viewCount desc, p.id desc
        """
    )
    fun pageByViews(
        @Param("owner") owner: Long,
        @Param("vis") vis: Visibility?,
        @Param("q") q: String?,
        @Param("hasCursor") hasCursor: Boolean,
        @Param("cursorViews") cursorViews: Long?,
        @Param("cursorId") cursorId: Long?,
        pageable: Pageable,
    ): List<CasePublishmentEntity>

    @Query(
        """
        select count(p) from CasePublishmentEntity p
        where p.ownerUserId = :owner
          and (:vis is null or p.visibility = :vis)
          and (:q is null or lower(p.title) like lower(concat('%', cast(:q as string), '%')))
        """
    )
    fun countByOwner(
        @Param("owner") owner: Long,
        @Param("vis") vis: Visibility?,
        @Param("q") q: String?,
    ): Long

    /**
     * 소유자 발행물 요약 집계 — visibility별 (count, sum(viewCount)) 행 반환.
     * 각 행: [Visibility, Long count, Long sumViews]. 없는 visibility 는 행 자체가 없음(0 으로 접힘).
     */
    @Query(
        """
        select p.visibility, count(p), coalesce(sum(p.viewCount), 0), coalesce(sum(p.downloadCount), 0)
        from CasePublishmentEntity p
        where p.ownerUserId = :owner
        group by p.visibility
        """
    )
    fun aggregateByOwner(@Param("owner") owner: Long): List<Array<Any>>
}

/** 공개 페이지 경량 조회 프로젝션(closed projection → status/owner/updatedAt 컬럼만 SELECT, jsonb 미조회). */
interface PublicPageMetaView {
    val status: PublishmentStatus
    val ownerUserId: Long
    val updatedAt: LocalDateTime
}
