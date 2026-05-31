package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository

/**
 * 케이스 태그 저장소. case sub-aggregate 안의 부수 객체 — `ErrorCaseRepositoryPort` 와 분리.
 * 멱등 add / 단건 remove + 케이스 cascade 삭제용.
 */
@Repository
interface ErrorCaseTagRepositoryPort {
    /** 케이스의 모든 태그 (createdAt ASC, id ASC). */
    fun findAllByErrorCaseId(errorCaseId: Long): List<String>

    fun count(errorCaseId: Long): Long

    /** 멱등 add — 이미 있으면 false, 새로 추가하면 true. */
    fun add(errorCaseId: Long, tag: String): Boolean

    /** 단건 remove — 삭제된 row 수(0 또는 1). */
    fun remove(errorCaseId: Long, tag: String): Int

    /** 케이스 삭제 cascade 용. */
    fun deleteAllByErrorCaseId(errorCaseId: Long)
}
