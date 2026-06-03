package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter.ErrorCaseRepositoryAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity.ErrorCaseEntity


interface ErrorCaseJpaRepository : JpaRepository<ErrorCaseEntity, Long> {

    @Query("SELECT e.ownerUserId FROM ErrorCaseEntity e WHERE e.id = :id")
    fun findOwnerUserIdById(@Param("id") id: Long): Long?

    // 동적 필터 + keyset 목록 조회는 ErrorCaseRepositoryAdapter 가 Criteria API 로 구현
    // (`:param IS NULL` + null 바인드의 Postgres 타입 추론 문제 회피).
}
