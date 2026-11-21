package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.insightapi.activity.infrastructure.jpa.ActivityEventEntity
import java.time.Instant

interface ActivityEventJpaRepository : JpaRepository<ActivityEventEntity, Long> {
    fun existsByIdempotencyKey(key: String): Boolean

    /**
     * TTL 만료 raw 를 *batch 단위로* DELETE — 한 트랜잭션에 너무 많이 잡으면 lock 길어짐.
     * 한 라운드에 [limit] 건 삭제. cleanup 잡이 0 반환까지 반복 호출.
     */
    @Modifying
    @Query(
        value = """
            DELETE FROM insight_activity_event
             WHERE id IN (
                SELECT id FROM insight_activity_event
                 WHERE created_at < :before
                 ORDER BY id
                 LIMIT :limit
             )
        """,
        nativeQuery = true,
    )
    fun deleteOlderThan(@Param("before") before: Instant, @Param("limit") limit: Int): Int

    // ── 제품 참여(engagement) 지표 집계 — ProductEngagementMetrics 배치 전용 ──────────────
    // 모두 사람 활동만(COALESCE(origin,'USER')='USER'): 에이전트 초안 반영분은 제외. 반환은 Array<Any>
    // 로 받아 Kotlin 에서 변환(네이티브 projection 의 timestamptz→Instant 매핑 편차 회피).

    /** CASE_CREATED(사람) 의 (userId, occurredAt) 전체 — 유저당 케이스수·간격·첫케이스·리텐션 계산용. */
    @Query(
        value = """
            SELECT user_id AS "userId", occurred_at AS "occurredAt"
              FROM insight_activity_event
             WHERE type = 'CASE_CREATED' AND COALESCE(origin, 'USER') = 'USER'
             ORDER BY user_id, occurred_at
        """,
        nativeQuery = true,
    )
    fun caseCreatedUserTimes(): List<Array<Any>>

    /** 최근 창 CASE_CREATED 수(출처별) — 수동 vs 에이전트 소스믹스. origin NULL 은 USER 로. */
    @Query(
        value = """
            SELECT COALESCE(origin, 'USER') AS "origin", COUNT(*) AS "cnt"
              FROM insight_activity_event
             WHERE type = 'CASE_CREATED' AND occurred_at >= :since
             GROUP BY COALESCE(origin, 'USER')
        """,
        nativeQuery = true,
    )
    fun caseCountsByOriginSince(@Param("since") since: Instant): List<Array<Any>>

    /** 최근 창 공개(PUBLIC) 케이스 수 / 전체 케이스 수 — 공개 발행 비율. (단일 행: [pub, tot]) */
    @Query(
        value = """
            SELECT COUNT(*) FILTER (WHERE meta_json->>'visibility' = 'PUBLIC') AS "pub",
                   COUNT(*) AS "tot"
              FROM insight_activity_event
             WHERE type = 'CASE_CREATED' AND occurred_at >= :since
        """,
        nativeQuery = true,
    )
    fun casePublicCountsSince(@Param("since") since: Instant): List<Array<Any>>

    /** 최근 창 특정 type 활동 수 — 예: CASE_RESOLVED(해결 활동). */
    @Query(
        value = """
            SELECT COUNT(*)
              FROM insight_activity_event
             WHERE type = :type AND occurred_at >= :since
        """,
        nativeQuery = true,
    )
    fun countByTypeSince(@Param("type") type: String, @Param("since") since: Instant): Long
}
