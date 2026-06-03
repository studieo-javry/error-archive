package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase

@Repository
interface ErrorCaseRepositoryPort {

    fun save(errorCase: ErrorCase): ErrorCase

    /** 전체 애그리거트 로드(스냅샷+스니펫+첨부 포함). 없으면 null. */
    fun findById(errorCaseId: Long): ErrorCase?

    /** 메타데이터/본문만 갱신(스니펫·첨부 연결은 건드리지 않음). */
    fun update(errorCase: ErrorCase): ErrorCase

    /** 목록 조회(요약). keyset 커서 기반, createdAt DESC, id DESC. limit 만큼 반환. */
    fun search(criteria: ErrorCaseSearchCriteria): List<ErrorCaseSummary>

    fun findOwnerUserIdById(errorCaseId: Long): Long?

    fun deleteById(errorCaseId: Long)
}
