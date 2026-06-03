package org.studieojavry.coreapi.errorcase.case.application.port

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

/**
 * 목록 조회 검색 조건. null 필터는 미적용. keyset 커서는 (createdAt, id) 기준.
 * `limit` 은 보통 size+1 로 넘겨 hasNext 판정에 쓴다.
 */
data class ErrorCaseSearchCriteria(
    val workspaceId: Long?,
    val ownerUserId: Long?,
    val status: ErrorCaseStatus?,
    val severityCode: Int?,
    val fingerprint: String?,
    val cursorCreatedAt: LocalDateTime?,
    val cursorId: Long?,
    val limit: Int
)

/** 목록 항목(요약) — 상세보다 가볍게(스니펫/첨부 본문 제외). */
data class ErrorCaseSummary(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: ErrorCaseStatus,
    val visibility: Visibility,
    val severityCode: Int?,
    val workspaceId: Long?,
    val fingerprint: String?,
    val exceptionClass: String?,
    val createdAt: LocalDateTime,
    val occurredAt: LocalDateTime?
)
