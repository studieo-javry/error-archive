package org.studieojavry.publishapi.publishment.presentation.dto

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import java.time.LocalDateTime

data class PublishmentResponse(
    val slug: String,
    val publicUrl: String,
    val markdownUrl: String,
    val pdfUrl: String,
    val ownerUserId: Long,
    val originalCaseId: Long,
    val title: String,
    val summary: String?,
    val visibility: String,
    val tags: List<String>,
    val stepCount: Int,
    val solutionCount: Int,
    val version: Int,
    val viewCount: Long,
    val downloadCount: Long,
    val publishedAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    /** 원본 case 라이프사이클 — LIVE / SOURCE_DELETED. FE 가 "원본 삭제됨" 배지에 사용. */
    val sourceState: String,
    /** 발행물 라이프사이클 — LIVE / UNPUBLISHED. FE 가 "Archived" 배지·"다시 공개" 버튼에 사용. */
    val status: String,
    /**
     * 원본이 발행 이후 수정됨(재발행 권장). 목록 조회에서만 채워짐 — 단건 응답은 null.
     * null = 판정 안 함/불가(단건 응답, core 조회 실패, 또는 원본 삭제).
     */
    val sourceStale: Boolean?,
    val options: PublishOptions,
    val content: ContentSnapshot,
) {
    companion object {
        fun from(
            p: CasePublishment,
            publicBaseUrl: String,
            sourceStale: Boolean? = null,
        ): PublishmentResponse = PublishmentResponse(
            slug = p.slug,
            publicUrl = "$publicBaseUrl/p/${p.slug}",
            markdownUrl = "$publicBaseUrl/api/v1/publishments/by-slug/${p.slug}/export.md",
            pdfUrl = "$publicBaseUrl/api/v1/publishments/by-slug/${p.slug}/export.pdf",
            ownerUserId = p.ownerUserId,
            originalCaseId = p.originalCaseId,
            title = p.title,
            summary = p.summary,
            visibility = p.visibility.name,
            tags = p.contentSnapshot.tags,
            stepCount = p.contentSnapshot.steps.size,
            solutionCount = p.contentSnapshot.solutions.size,
            version = p.version,
            viewCount = p.viewCount,
            downloadCount = p.downloadCount,
            publishedAt = p.publishedAt,
            updatedAt = p.updatedAt,
            sourceState = p.sourceState.name,
            status = p.status.name,
            sourceStale = sourceStale,
            options = p.options,
            content = p.contentSnapshot,
        )
    }
}
