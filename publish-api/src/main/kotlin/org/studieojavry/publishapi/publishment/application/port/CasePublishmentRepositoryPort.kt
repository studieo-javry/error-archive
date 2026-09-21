package org.studieojavry.publishapi.publishment.application.port

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.PublishmentStatus
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * 공개 페이지 게이팅 + 렌더 캐시 key 용 **경량 메타** — content(jsonb) 역직렬화 없이 조회.
 * `updatedAt` 은 모든 편집(재발행/메타편집)에서 갱신 → 캐시 무효화 토큰.
 */
data class PublicPageMeta(
    val status: PublishmentStatus,
    val ownerUserId: Long,
    val updatedAt: LocalDateTime,
)

/** 내 발행물 목록 정렬 기준. */
enum class MyPublishmentSort {
    PUBLISHED,   // publishedAt DESC (default)
    VIEWS;       // viewCount DESC

    companion object {
        fun fromCode(code: String?): MyPublishmentSort = when (code?.lowercase()) {
            "views" -> VIEWS
            else -> PUBLISHED
        }
    }
}

/**
 * 내 발행물 목록 조회 criteria (keyset 페이징).
 *
 * `cursorValue` = 직전 페이지 마지막 행의 *정렬키 값의 문자열*:
 *  - PUBLISHED → ISO `LocalDateTime`
 *  - VIEWS → `Long`(viewCount)
 * `cursorValue`/`cursorId` 둘 다 null 이면 첫 페이지.
 * `limit` 은 어댑터가 실제로 fetch 할 개수 (use case 가 hasNext 판정 위해 +1 해서 전달).
 */
data class MyPublishmentQuery(
    val ownerUserId: Long,
    val q: String?,
    val visibility: Visibility?,
    val sort: MyPublishmentSort,
    val cursorValue: String?,
    val cursorId: Long?,
    val limit: Int,
)

/**
 * 소유자 발행물 요약 집계 — 툴바 요약(`N published · N public · N unlisted · Nk views`)용.
 * **검색/필터와 무관하게 소유자의 전체 발행물 기준**으로 계산(필터 정책이 바뀌어도 요약은 고정 의미).
 */
data class OwnerPublishmentSummary(
    val total: Long,
    val publicCount: Long,
    val unlistedCount: Long,
    val totalViews: Long,
    val totalDownloads: Long,
)

interface CasePublishmentRepositoryPort {
    fun save(p: CasePublishment): CasePublishment
    fun update(p: CasePublishment): CasePublishment
    fun findBySlug(slug: String): CasePublishment?
    /** 공개 페이지 경량 메타 조회(jsonb 미조회) — 게이팅 + 캐시 key. 없으면 null. */
    fun findPublicMetaBySlug(slug: String): PublicPageMeta?
    fun findById(id: Long): CasePublishment?
    /** 케이스당 1 canonical 발행물 정책 — 원본 case 로 기존 발행물 조회(없으면 null). */
    fun findByOriginalCaseId(caseId: Long): CasePublishment?
    fun deleteById(id: Long)
    fun listByOwner(ownerUserId: Long): List<CasePublishment>
    fun listByVisibility(visibility: Visibility): List<CasePublishment>

    /** discovery(sitemap/공개 목록) — PUBLIC 이고 status=LIVE 인 것만. UNPUBLISHED 자동 제외. */
    fun listPublicDiscoverable(): List<CasePublishment>
    fun incrementViewCount(slug: String)
    fun incrementDownloadCount(slug: String)
    fun existsBySlug(slug: String): Boolean
    /** 원본 case 삭제 감지 시 해당 발행물을 SOURCE_DELETED 로 표시(발행물 자체는 유지). */
    fun markSourceDeleted(id: Long)

    /** 내 발행물 keyset 페이지 (검색/필터/정렬). `limit` 만큼 반환(정렬 순). */
    fun pageByOwner(query: MyPublishmentQuery): List<CasePublishment>

    /** 내 발행물 총 개수 (검색/필터 동일, 커서 무시) — 첫 페이지 totalCount 용. */
    fun countByOwner(query: MyPublishmentQuery): Long

    /** 소유자 발행물 요약 집계(필터 무관 전체) — visibility별 카운트 + 총 조회수 합계. */
    fun summaryOf(ownerUserId: Long): OwnerPublishmentSummary
}
