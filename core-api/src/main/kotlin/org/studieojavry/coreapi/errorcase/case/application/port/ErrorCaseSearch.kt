package org.studieojavry.coreapi.errorcase.case.application.port

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

/**
 * 목록 조회 검색 조건. null 필터는 미적용. keyset 커서는 (createdAt, id) 기준.
 * `limit` 은 보통 size+1 로 넘겨 hasNext 판정에 쓴다.
 *
 * 향후 필터(검색어 q / 태그 / language 등) 추가 시 본 클래스만 확장하고
 * adapter 의 Criteria predicate 분기에 한 줄 추가하면 된다.
 */
data class ErrorCaseSearchCriteria(
    val workspaceId: Long?,
    val ownerUserId: Long?,
    val status: ErrorCaseStatus?,
    val fingerprint: String?,
    /** 공개 검색 endpoint 가 PUBLIC 으로 강제하기 위해 사용. null 이면 미적용. */
    val visibility: Visibility?,
    val cursorCreatedAt: LocalDateTime?,
    val cursorId: Long?,
    val limit: Int,
    /** 검색어 — title 또는 tag 부분 매치 (case-insensitive). null 이면 미적용. */
    val q: String? = null,
    /** 정렬 기준. Default = CREATED. cursor keyset 도 이 필드 기반으로 동작한다. */
    val sortBy: SortBy = SortBy.CREATED,
)

/** ErrorCase 목록 정렬 옵션. cursor keyset 도 이 필드 기반. */
enum class SortBy {
    /** case.createdAt DESC (기본). */
    CREATED,
    /** case.updatedAt DESC — 최근 활동 순. */
    UPDATED,
    ;
    companion object {
        fun fromCode(code: String?): SortBy = when (code?.lowercase()) {
            "updated" -> UPDATED
            else -> CREATED // default 포함 unknown
        }
    }
}

/**
 * 목록 항목(요약) — 상세보다 가볍게(스니펫/첨부 본문 제외).
 *
 * `tags` 는 케이스에 붙은 태그 전체 (정렬 createdAt asc).
 * `descriptionRaw` 는 use case 가 preview 생성에 사용하는 원본 — 응답에 직접 노출하지 않음.
 */
data class ErrorCaseSummary(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: ErrorCaseStatus,
    val visibility: Visibility,
    val workspaceId: Long?,
    val fingerprint: String?,
    val exceptionClass: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val occurredAt: LocalDateTime?,
    val tags: List<String>,
    val descriptionRaw: String?,
    /** RESOLVED 로 전환된 마지막 시각 — 없으면 null. timeline 의 RESOLVED 활동 시각. */
    val resolvedAt: LocalDateTime? = null,
    val resolvedByUserId: Long? = null,
)
