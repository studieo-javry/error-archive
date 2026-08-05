package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.case.application.usecase.GetSuggestedFolloweesUseCase

/**
 * suggested-followees 응답 — `AuthorSummaryResponse` 와 동일 schema.
 * reason 미포함 (확정 spec). FE 는 단순 카드 렌더.
 */
data class SuggestedFolloweesResponse(
    val items: List<AuthorSummaryResponse>,
) {
    companion object {
        fun from(result: GetSuggestedFolloweesUseCase.Result) = SuggestedFolloweesResponse(
            items = result.items.mapNotNull { userId ->
                result.authors[userId]?.let { AuthorSummaryResponse.from(it) }
            }
        )
    }
}
