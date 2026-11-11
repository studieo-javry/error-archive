package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.shared.application.usecase.MarkerTokens

/**
 * Step 본문이 참조하는 `@attach(x)`/`@snippet(x)` 마커는 반드시 **이 Case 에 링크된** 첨부/스니펫이어야 한다.
 * 링크 권한은 Case 가 가지므로(첨부는 error_case_id 1:1, Case 의 attachmentMarkerIds 로 링크), Step 본문은 참조 전용.
 * 미링크/외부 마커를 참조하면 422 — "먼저 Case 에 링크하라".
 */
internal fun validateStepBodyMarkers(body: String?, errorCase: ErrorCase) {
    if (body.isNullOrBlank()) return
    val linkedAttachments = errorCase.attachments.map { it.markerId }.toSet()
    MarkerTokens.attachIds(body).firstOrNull { it !in linkedAttachments }
        ?.let { throw UnknownAttachmentReferenceException(it) }
    val linkedSnippets = errorCase.snippets.map { it.markerId }.toSet()
    MarkerTokens.snippetIds(body).firstOrNull { it !in linkedSnippets }
        ?.let { throw UnknownSnippetReferenceException(it) }
}

/** Step 본문이 이 Case 에 링크되지 않은 첨부 marker 를 참조 → 422. */
class UnknownAttachmentReferenceException(val markerId: String) :
    RuntimeException("step body references attachment '$markerId' not linked to this case")

/** Step 본문이 이 Case 에 링크되지 않은 snippet marker 를 참조 → 422. */
class UnknownSnippetReferenceException(val markerId: String) :
    RuntimeException("step body references snippet '$markerId' not linked to this case")
