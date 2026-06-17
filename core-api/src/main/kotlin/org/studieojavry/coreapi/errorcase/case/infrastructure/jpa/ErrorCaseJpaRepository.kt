package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseEntity


interface ErrorCaseJpaRepository : JpaRepository<ErrorCaseEntity, Long> {

    @Query("SELECT e.ownerUserId FROM ErrorCaseEntity e WHERE e.id = :id")
    fun findOwnerUserIdById(@Param("id") id: Long): Long?

    fun findAllByOwnerUserIdOrderByUpdatedAtDescIdDesc(
        ownerUserId: Long,
        pageable: Pageable,
    ): List<ErrorCaseEntity>

    fun findAllByIdIn(ids: Collection<Long>): List<ErrorCaseEntity>

    fun findAllByResolvedByUserIdAndResolvedAtGreaterThanEqualOrderByResolvedAtDescIdDesc(
        resolvedByUserId: Long,
        since: java.time.LocalDateTime,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ErrorCaseEntity>

    fun countByOwnerUserIdAndCreatedAtGreaterThanEqual(
        ownerUserId: Long,
        since: java.time.LocalDateTime,
    ): Long

    fun countByResolvedByUserIdAndResolvedAtGreaterThanEqual(
        resolvedByUserId: Long,
        since: java.time.LocalDateTime,
    ): Long

    fun findAllByOwnerUserIdInAndVisibilityOrderByCreatedAtDescIdDesc(
        ownerUserIds: Collection<Long>,
        visibility: org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility,
        pageable: Pageable,
    ): List<ErrorCaseEntity>

    @Query("""
        select e.ownerUserId as userId, count(e) as count
          from ErrorCaseEntity e
         where e.visibility = org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility.PUBLIC
           and e.createdAt >= :since
         group by e.ownerUserId
         order by count(e) desc
    """)
    fun findTopOwnersOfRecentPublic(
        @Param("since") since: java.time.LocalDateTime,
        pageable: Pageable,
    ): List<org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.UserCountProjection>

    /**
     * PUBLIC case 작성자 distinct, random 순. native query — JPQL 에 `random()` 미지원.
     * `DISTINCT + ORDER BY random()` 은 Postgres 가 reject 하므로 `GROUP BY` 로 dedupe.
     * ORDER BY random() 은 작은 데이터셋(MVP) 한정 허용. PUBLIC case 총수가 늘면 sample 전략 재검토.
     */
    @Query(
        value = """
            select owner_user_id
              from error_case
             where visibility = 'PUBLIC'
             group by owner_user_id
             order by random()
             limit :lim
        """,
        nativeQuery = true,
    )
    fun findRandomPublicCaseOwners(@Param("lim") limit: Int): List<Long>

    // 동적 필터 + keyset 목록 조회는 ErrorCaseRepositoryAdapter 가 Criteria API 로 구현
    // (`:param IS NULL` + null 바인드의 Postgres 타입 추론 문제 회피).
}
