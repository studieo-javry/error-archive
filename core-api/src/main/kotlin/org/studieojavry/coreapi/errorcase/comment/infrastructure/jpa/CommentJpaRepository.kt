package org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.comment.infrastructure.jpa.entity.CommentEntity
import java.time.LocalDateTime

interface CommentJpaRepository : JpaRepository<CommentEntity, Long> {
    fun findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(errorCaseId: Long): List<CommentEntity>

    @Modifying(clearAutomatically = true)
    @Query("delete from CommentEntity c where c.errorCaseId = :caseId")
    fun deleteAllByErrorCaseId(@Param("caseId") errorCaseId: Long): Int

    // ── Recent Activity ───────────────────────────────────────
    @Query("""
        select c from CommentEntity c
         where c.authorUserId = :authorUserId
           and c.deletedAt is null
           and c.createdAt >= :since
         order by c.createdAt desc, c.id desc
    """)
    fun findRecentByAuthor(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
        pageable: Pageable,
    ): List<CommentEntity>

    @Query("""
        select c.errorCaseId as errorCaseId, max(c.createdAt) as value
          from CommentEntity c
         where c.errorCaseId in :caseIds and c.deletedAt is null
         group by c.errorCaseId
    """)
    fun findMaxCreatedAtGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseDateProjection>

    @Query("""
        select c.errorCaseId as errorCaseId, count(c) as count
          from CommentEntity c
         where c.errorCaseId in :caseIds and c.deletedAt is null
         group by c.errorCaseId
    """)
    fun countGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
    ): List<CaseCountProjection>

    @Query("""
        select count(c) from CommentEntity c
         where c.errorCaseId = :errorCaseId
           and c.deletedAt is null
           and c.createdAt > :since
           and c.authorUserId <> :excludeUserId
    """)
    fun countSinceExcludingAuthor(
        @Param("errorCaseId") errorCaseId: Long,
        @Param("since") since: LocalDateTime,
        @Param("excludeUserId") excludeUserId: Long,
    ): Long

    @Query("""
        select c.errorCaseId as errorCaseId, count(c) as count
          from CommentEntity c
         where c.errorCaseId in :caseIds
           and c.deletedAt is null
           and c.createdAt > :since
         group by c.errorCaseId
    """)
    fun countByCaseIdsSinceGrouped(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("since") since: LocalDateTime,
    ): List<CaseCountProjection>

    /** unread 계산용 — globalSince 이후 활동을 case-id 별로 fetch. in-memory 에서 case 별 lastViewedAt 필터. */
    @Query("""
        select c.errorCaseId as errorCaseId, c.id as activityId,
               c.createdAt as createdAt, c.authorUserId as authorUserId,
               'COMMENT' as source
          from CommentEntity c
         where c.errorCaseId in :caseIds
           and c.deletedAt is null
           and c.createdAt > :globalSince
    """)
    fun findActivitiesByCaseIdsSince(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("globalSince") globalSince: LocalDateTime,
    ): List<CaseActivityProjection>

    /**
     * unread 집계 (DB-side, 일괄). case 별 lastViewedAt(case_view LEFT JOIN, 미열람이면 태초 1970) 이후 +
     * author != userId 인 미삭제 댓글 수. **활동 전량을 앱 메모리로 물질화하지 않도록** count 를 DB 가 계산한다
     * (기존 findActivitiesByCaseIdsSince + in-memory groupingBy 를 대체). 반환 행 수는 caseIds 크기 이하.
     */
    @Query(
        value = """
            SELECT a.error_case_id AS "errorCaseId", count(*) AS "count"
              FROM error_case_comment a
              LEFT JOIN case_view v
                ON v.error_case_id = a.error_case_id AND v.user_id = :userId
             WHERE a.error_case_id IN (:caseIds)
               AND a.deleted_at IS NULL
               AND a.author_user_id <> :userId
               AND a.created_at > COALESCE(v.last_viewed_at, TIMESTAMP '1970-01-01')
             GROUP BY a.error_case_id
        """,
        nativeQuery = true,
    )
    fun countUnreadByCaseIds(
        @Param("caseIds") caseIds: Collection<Long>,
        @Param("userId") userId: Long,
    ): List<CaseCountProjection>

    @Query("""
        select count(c) from CommentEntity c
         where c.authorUserId = :authorUserId
           and c.deletedAt is null
           and c.createdAt >= :since
    """)
    fun countByAuthorSince(
        @Param("authorUserId") authorUserId: Long,
        @Param("since") since: LocalDateTime,
    ): Long
}

interface CaseDateProjection {
    val errorCaseId: Long
    val value: LocalDateTime
}

interface CaseCountProjection {
    val errorCaseId: Long
    val count: Long
}

/** unread 계산용 활동 row — 4 도메인 공용. case-id 별로 (createdAt > caseLastViewed AND authorUserId != me) 필터. */
interface CaseActivityProjection {
    val errorCaseId: Long
    /** 도메인별 PK (comment.id / step.id / solution.id) — watchlist-feed 의 deep link fragment 용. */
    val activityId: Long
    val createdAt: LocalDateTime
    val authorUserId: Long
    /** 'COMMENT' / 'STEP' / 'SOLUTION' — JPQL literal 로 주입. 3 도메인 fan-in 후 source 식별용. */
    val source: String
}
