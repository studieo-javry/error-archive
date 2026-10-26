package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.DescriptionPreview
import org.studieojavry.coreapi.errorcase.case.application.usecase.FindSimilarCasesUseCase

/**
 * 유사 에러 추천 항목. 목록 요약과 같은 표시 필드 + `score`(0~1) + `matchedTerms`(하이라이트용)
 * + `likelyDuplicate`(서버 판정 "중복 가능성 높음": score ≥ 0.85 AND 예외 클래스 동일).
 * 게이지 색/소프트 밴드는 FE 가 `score` 로, 강한 중복 경고는 `likelyDuplicate` 로 결정한다.
 */
data class SimilarCaseResponse(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val status: String,
    val visibility: String,
    val workspaceId: Long?,
    val tags: List<String>,
    val descriptionPreview: String?,
    val score: Double,
    val matchedTerms: List<String>,
    val likelyDuplicate: Boolean,
) {
    companion object {
        fun from(v: FindSimilarCasesUseCase.View): SimilarCaseResponse {
            val s = v.summary
            return SimilarCaseResponse(
                id = s.id,
                ownerUserId = s.ownerUserId,
                title = s.title,
                status = s.status.name,
                visibility = s.visibility.name,
                workspaceId = s.workspaceId,
                tags = s.tags,
                descriptionPreview = DescriptionPreview.of(s.descriptionRaw),
                score = v.score,
                matchedTerms = v.matchedTerms,
                likelyDuplicate = v.likelyDuplicate,
            )
        }
    }
}

/** 유사 에러 추천 응답 — 유사도 내림차순. */
data class SimilarCaseListResponse(
    val items: List<SimilarCaseResponse>,
)
