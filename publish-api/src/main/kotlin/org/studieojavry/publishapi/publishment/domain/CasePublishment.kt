package org.studieojavry.publishapi.publishment.domain

import java.time.LocalDateTime

/**
 * publish 된 케이스 1건 — 원본 case 와 분리된 자산
 *
 *  - `slug` 가 URL 식별자 (`/p/{slug}`)
 *  - `contentSnapshot` 이 publish 시점 불변 스냅샷
 *  - `version` 은 재발행마다 +1
 *  - PUBLIC 만 검색 / discovery 에 노출
 */
class CasePublishment private constructor(
    val id: Long?,
    val slug: String,
    val ownerUserId: Long,
    val originalCaseId: Long,
    var title: String,
    var summary: String?,
    var visibility: Visibility,
    var contentSnapshot: ContentSnapshot,
    var options: PublishOptions,
    var version: Int,
    /**
     * 공개 페이지(`/p/{slug}`) 조회수 — 읽기 전용 스냅샷.
     * 변경은 오직 native `incrementViewCount(slug)` 로만(원자 UPDATE). 도메인 인메모리 변경 금지(val).
     */
    val viewCount: Long,
    /** export(PDF/MD) 다운로드 수 — view 와 분리 지표. 변경은 native `incrementDownloadCount(slug)` 전용. */
    val downloadCount: Long,
    val publishedAt: LocalDateTime,
    var updatedAt: LocalDateTime,
    /** 원본 case 라이프사이클 상태 — 기본 LIVE. lazy 판정으로 SOURCE_DELETED 로 전이. */
    var sourceState: SourceState,
    /** 발행물 라이프사이클 — LIVE(공개 중) / UNPUBLISHED(내려짐). sourceState 와 직교. */
    var status: PublishmentStatus = PublishmentStatus.LIVE,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(title.length <= TITLE_MAX) { "title must be $TITLE_MAX chars or less" }
        require(summary == null || summary!!.length <= SUMMARY_MAX) {
            "summary must be $SUMMARY_MAX chars or less"
        }
    }

    /**
     * 재발행. version 은 스냅샷 내용이 실제로 바뀐 경우에만 +1 —
     * 제목/요약/visibility/options 만 바뀌거나 아무 변화가 없으면 version 유지
     * (ContentSnapshot 은 data class 라 `==` 구조 비교로 판정)
     */
    fun republish(
        newTitle: String,
        newSummary: String?,
        newVisibility: Visibility,
        newSnapshot: ContentSnapshot,
        newOptions: PublishOptions,
    ): CasePublishment {
        require(newTitle.isNotBlank()) { "title must not be blank" }
        val snapshotChanged = newSnapshot != this.contentSnapshot
        this.title = newTitle
        this.summary = newSummary
        this.visibility = newVisibility
        this.contentSnapshot = newSnapshot
        this.options = newOptions
        if (snapshotChanged) this.version += 1
        this.updatedAt = LocalDateTime.now()
        return this
    }

    /**
     * 경량 메타 편집(A축) — 스냅샷/원본 조회 없이 표현 필드만 교체. version 유지, updatedAt 갱신.
     * 원본 삭제(SOURCE_DELETED)여도 허용. partial 은 usecase 에서 해소해 non-null 로 전달.
     */
    fun editMetadata(
        newTitle: String,
        newSummary: String?,
        newVisibility: Visibility,
        newOptions: PublishOptions,
    ) {
        require(newTitle.isNotBlank()) { "title must not be blank" }
        require(newTitle.length <= TITLE_MAX) { "title must be $TITLE_MAX chars or less" }
        require(newSummary == null || newSummary.length <= SUMMARY_MAX) { "summary too long" }
        this.title = newTitle
        this.summary = newSummary
        this.visibility = newVisibility
        this.options = newOptions
        this.updatedAt = LocalDateTime.now()
    }

    /** 내리기(soft) — 공개 라우트 410, 목록/discovery 제외. slug·스냅샷·counters 보존. */
    fun unpublish() {
        this.status = PublishmentStatus.UNPUBLISHED
        this.updatedAt = LocalDateTime.now()
    }

    /** 다시 공개 — UNPUBLISHED → LIVE 복구. */
    fun restore() {
        this.status = PublishmentStatus.LIVE
        this.updatedAt = LocalDateTime.now()
    }

    /** 원본 case 가 삭제됐음을 표시(발행물 자체는 유지). 재발행 불가 상태 */
    fun markSourceDeleted() {
        this.sourceState = SourceState.SOURCE_DELETED
    }

    companion object {
        const val TITLE_MAX = 200
        const val SUMMARY_MAX = 500

        fun create(
            slug: String,
            ownerUserId: Long,
            originalCaseId: Long,
            title: String,
            summary: String?,
            visibility: Visibility,
            contentSnapshot: ContentSnapshot,
            options: PublishOptions,
        ): CasePublishment {
            val now = LocalDateTime.now()
            return CasePublishment(
                id = null, slug = slug, ownerUserId = ownerUserId,
                originalCaseId = originalCaseId,
                title = title, summary = summary,
                visibility = visibility,
                contentSnapshot = contentSnapshot,
                options = options,
                version = 1, viewCount = 0, downloadCount = 0,
                publishedAt = now, updatedAt = now,
                sourceState = SourceState.LIVE,
            )
        }

        fun rehydrate(
            id: Long, slug: String, ownerUserId: Long, originalCaseId: Long,
            title: String, summary: String?, visibility: Visibility,
            contentSnapshot: ContentSnapshot, options: PublishOptions,
            version: Int, viewCount: Long, downloadCount: Long,
            publishedAt: LocalDateTime, updatedAt: LocalDateTime,
            sourceState: SourceState = SourceState.LIVE,
            status: PublishmentStatus = PublishmentStatus.LIVE,
        ) = CasePublishment(
            id, slug, ownerUserId, originalCaseId,
            title, summary, visibility, contentSnapshot, options,
            version, viewCount, downloadCount, publishedAt, updatedAt, sourceState, status,
        )
    }
}
