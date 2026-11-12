package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseEntity


interface ErrorCaseJpaRepository : JpaRepository<ErrorCaseEntity, Long> {

    /**
     * 에이전트 case:read — 본인 소유 + 선택적 바인딩 필터 + query LIKE(제목·설명·예외클래스·예외메시지).
     * `:q` 는 호출측에서 소문자 + `%…%` 로 감싸 전달(null 이면 전체). projectBindingId null → 미귀속 포함 전체.
     */
    @Query(
        "SELECT e FROM ErrorCaseEntity e WHERE e.ownerUserId = :owner " +
            "AND (:bindingId IS NULL OR e.projectBindingId = :bindingId) " +
            "AND (:q IS NULL OR lower(e.title) LIKE :q OR lower(e.description) LIKE :q " +
            "OR lower(e.snapshot.exceptionClass) LIKE :q OR lower(e.snapshot.exceptionMessage) LIKE :q) " +
            "ORDER BY e.createdAt DESC, e.id DESC",
    )
    fun searchOwnedCasesForAgent(
        @Param("owner") owner: Long,
        @Param("bindingId") bindingId: Long?,
        @Param("q") q: String?,
        pageable: Pageable,
    ): List<ErrorCaseEntity>

    @Query("SELECT e.ownerUserId FROM ErrorCaseEntity e WHERE e.id = :id")
    fun findOwnerUserIdById(@Param("id") id: Long): Long?

    @Query("SELECT e.meta.workspaceId FROM ErrorCaseEntity e WHERE e.id = :id")
    fun findWorkspaceIdById(@Param("id") id: Long): Long?

    /** 데모 시드 표식 — 엔티티 매핑에서 제외된 컬럼이라 native update 로만 기록. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE error_case SET demo_seed_version = :v WHERE id IN (:ids)", nativeQuery = true)
    fun markDemo(@Param("ids") ids: Collection<Long>, @Param("v") seedVersion: Int): Int

    /** 공개 쇼케이스(워크스페이스 없는 데모 시드) id → 시드 버전. */
    @Query("SELECT e.id AS id, e.demoSeedVersion AS version FROM ErrorCaseEntity e WHERE e.demoSeedVersion IS NOT NULL AND e.meta.workspaceId IS NULL")
    fun findDemoShowcase(): List<DemoSeedProjection>

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
           and e.demoSeedVersion is null
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
               and demo_seed_version is null
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

interface DemoSeedProjection {
    val id: Long
    val version: Int
}
