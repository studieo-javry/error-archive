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
     *
     * 개선: 기존 `GROUP BY owner ORDER BY random()` 은 PUBLIC 전체를 Seq Scan(케이스 수에 O(N)) —
     * 부하 테스트에서 20k 8.6ms → 100k 44.5ms 로 선형 확인. **TABLESAMPLE SYSTEM 으로 페이지 표본만
     * 스캔**해 O(표본)으로 낮춘다(표본 내 GROUP BY dedupe + random 정렬). 이 쿼리는 신호 부족 시
     * 채우는 last-resort fallback 이라 표본이 약간 적어도 허용된다.
     * 주의: 표본 비율(현재 2%)은 PUBLIC 규모에 맞춰 조정 필요 — 소규모에선 결과가 적을 수 있고,
     * 초대규모는 사전계산 추천풀(주기 갱신 캐시)로 대체 검토.
     */
    @Query(
        value = """
            select owner_user_id
              from error_case tablesample system (2)
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
