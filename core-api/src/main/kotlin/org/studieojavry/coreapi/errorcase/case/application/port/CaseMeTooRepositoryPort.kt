package org.studieojavry.coreapi.errorcase.case.application.port

import org.studieojavry.coreapi.errorcase.case.domain.model.CaseMeToo

interface CaseMeTooRepositoryPort {

    /** 멱등 — 이미 있으면 기존 1건 반환, 없으면 새로 저장 후 반환. */
    fun add(errorCaseId: Long, userId: Long): CaseMeToo

    /** 없어도 0 반환 (멱등). */
    fun remove(errorCaseId: Long, userId: Long): Int

    fun listByCaseId(errorCaseId: Long): List<CaseMeToo>

    fun count(errorCaseId: Long): Long

    fun exists(errorCaseId: Long, userId: Long): Boolean

    /** 케이스 삭제 시 cascade 정리. */
    fun deleteAllByCaseId(errorCaseId: Long): Int
}
