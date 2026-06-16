package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter.ErrorCaseRepositoryAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseEntity
import java.time.LocalDateTime


interface ErrorCaseJpaRepository : JpaRepository<ErrorCaseEntity, Long> {

    @Query("SELECT e.ownerUserId FROM ErrorCaseEntity e WHERE e.id = :id")
    fun findOwnerUserIdById(@Param("id") id: Long): Long?

    fun findAllByIdIn(ids: Collection<Long>): List<ErrorCaseEntity>

    // ── Recent Activity (home dashboard) ──────────────────────
    /** 소유자의 최근 활동 케이스 — updatedAt DESC, id DESC. */
    fun findAllByOwnerUserIdOrderByUpdatedAtDescIdDesc(
        ownerUserId: Long,
        pageable: Pageable,
    ): List<ErrorCaseEntity>

    /** 내가 RESOLVED 로 전환한 케이스 (since 이후) — resolvedAt DESC, id DESC. */
    fun findAllByResolvedByUserIdAndResolvedAtGreaterThanEqualOrderByResolvedAtDescIdDesc(
        resolvedByUserId: Long,
        resolvedAt: LocalDateTime,
        pageable: Pageable,
    ): List<ErrorCaseEntity>

    /** since 이후 owner 가 생성한 케이스 수. */
    fun countByOwnerUserIdAndCreatedAtGreaterThanEqual(
        ownerUserId: Long,
        createdAt: LocalDateTime,
    ): Long

    /** since 이후 actor 가 RESOLVED 로 전환한 케이스 수. */
    fun countByResolvedByUserIdAndResolvedAtGreaterThanEqual(
        resolvedByUserId: Long,
        resolvedAt: LocalDateTime,
    ): Long

    // 동적 필터 + keyset 목록 조회는 ErrorCaseRepositoryAdapter 가 Criteria API 로 구현
    // (`:param IS NULL` + null 바인드의 Postgres 타입 추론 문제 회피).
}
