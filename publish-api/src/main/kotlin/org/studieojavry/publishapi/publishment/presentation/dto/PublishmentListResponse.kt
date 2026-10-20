package org.studieojavry.publishapi.publishment.presentation.dto

import org.studieojavry.publishapi.publishment.application.usecase.ContentPreview
import org.studieojavry.publishapi.publishment.application.usecase.ListMyPublishmentsUseCase
import java.time.LocalDateTime

/**
 * Library > My Publishments 목록의 slim 항목 — **`content`(ContentSnapshot) 제외**.
 * 목록엔 스텝/스니펫 본문이 불필요하므로 카운트만 노출(payload 경량화).
 * 발행물 본문 열람은 공개 페이지 `/p/{slug}`(SSR) 또는 export(.md/.pdf)로.
 */
data class PublishmentListItemResponse(
    val slug: String,
    val title: String,
    val summary: String?,
    /**
     * 저자 요약(summary)이 없을 때 카드에 보여줄 **본문 미리보기** — 스냅샷 description 에서 파생
     * (마커 `@snippet`/`@attach` → `[code]`/`[file]` 치환, ~160자). description 도 비면 null.
     * FE 는 `summary ?? descriptionPreview` 로 렌더.
     */
    val descriptionPreview: String?,
    /** 원본 error case id — 카드의 "from #{id}" 링크용. */
    val originalCaseId: Long,
    val visibility: String,
    val sourceState: String,
    /** 발행물 라이프사이클 — LIVE / UNPUBLISHED (Archived 배지용). */
    val status: String,
    /** 원본이 발행 이후 수정됨(재발행 권장). null = 판정 불가(core 조회 실패/원본 삭제). */
    val sourceStale: Boolean?,
    val version: Int,
    val viewCount: Long,
    val downloadCount: Long,
    val stepCount: Int,
    val solutionCount: Int,
    val tags: List<String>,
    val publishedAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val publicUrl: String,
    /** Markdown 내보내기 링크 — 기능 미출시로 현재 항상 null("도입 예정"). [PublishmentResponse.markdownUrl] 참고. */
    val markdownUrl: String?,
    val pdfUrl: String,
) {
    companion object {
        fun from(item: ListMyPublishmentsUseCase.Item, publicBaseUrl: String): PublishmentListItemResponse {
            val p = item.publishment
            return PublishmentListItemResponse(
                slug = p.slug,
                title = p.title,
                summary = p.summary,
                descriptionPreview = ContentPreview.of(p.contentSnapshot.description),
                originalCaseId = p.originalCaseId,
                visibility = p.visibility.name,
                sourceState = p.sourceState.name,
                status = p.status.name,
                sourceStale = item.sourceStale,
                version = p.version,
                viewCount = p.viewCount,
                downloadCount = p.downloadCount,
                stepCount = p.contentSnapshot.steps.size,
                solutionCount = p.contentSnapshot.solutions.size,
                tags = p.contentSnapshot.tags,
                publishedAt = p.publishedAt,
                updatedAt = p.updatedAt,
                publicUrl = "$publicBaseUrl/p/${p.slug}",
                markdownUrl = null, // 도입 예정 — export.md 핸들러 비활성
                pdfUrl = "$publicBaseUrl/api/v1/publishments/by-slug/${p.slug}/export.pdf",
            )
        }
    }
}

/** Load More 봉투 — items + nextCursor + hasNext + totalCount(첫 페이지만). */
data class PublishmentListResponse(
    val items: List<PublishmentListItemResponse>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val totalCount: Long?,
)

/**
 * Explore(공개 목록) 카드 항목 — 누구나 볼 수 있는 PUBLIC+LIVE 발행물. 소유자 전용 필드(sourceStale 등) 없음.
 * 카드 → 공개 페이지 `/p/{slug}` 링크. 본문(content)은 제외(미리보기만).
 */
data class PublicPublishmentItemResponse(
    val slug: String,
    val title: String,
    val summary: String?,
    val descriptionPreview: String?,
    val author: String?,
    val tags: List<String>,
    val stepCount: Int,
    val solutionCount: Int,
    val viewCount: Long,
    val publishedAt: LocalDateTime,
    val publicUrl: String,
) {
    companion object {
        fun from(p: org.studieojavry.publishapi.publishment.domain.CasePublishment, publicBaseUrl: String) =
            PublicPublishmentItemResponse(
                slug = p.slug,
                title = p.title,
                summary = p.summary,
                descriptionPreview = ContentPreview.of(p.contentSnapshot.description),
                author = p.options.authorDisplayName,
                tags = p.contentSnapshot.tags,
                stepCount = p.contentSnapshot.steps.size,
                solutionCount = p.contentSnapshot.solutions.size,
                viewCount = p.viewCount,
                publishedAt = p.publishedAt,
                publicUrl = "$publicBaseUrl/p/${p.slug}",
            )
    }
}

/**
 * 공개 Explore 목록(keyset 커서 페이징) 봉투. 첫 페이지는 `cursor` 생략, 이후 `nextCursor` 전달.
 * `hasNext=false` 면 끝. (소유자 목록 `PublishmentListResponse` 와 동일 페이징 방식.)
 */
data class PublicPublishmentListResponse(
    val items: List<PublicPublishmentItemResponse>,
    val nextCursor: String?,
    val hasNext: Boolean,
)
