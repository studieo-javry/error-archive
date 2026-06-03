package org.studieojavry.coreapi.errorcase.snippet.domain.model

import java.time.Instant
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment


/**
 * 코드 스니펫. 첨부(Attachment)와 동일하게 **먼저 독립 생성**되어 markerId 를 받고,
 * 에러케이스 생성 시 markerId 로 연결된다(`errorCaseId` 세팅). 본문 `@snippet(markerId)` 마커로
 * 인라인 임베드도 가능하지만, 연결만 되고 본문에 안 들어간 스니펫은 "관련 코드"로 노출된다.
 */
class CodeSnippet(
    val markerId: String,
    val title: String,
    val language: String,
    val filePathOrClass: String?,
    val lineRange: String?,
    val caption: String?,
    val code: String,
    val uploadedByUserId: Long,
    val uploadedAt: Instant,
    val errorCaseId: Long? = null
) {
    init {
        require(markerId.isNotEmpty()) { "Marker ID cannot be empty" }
        require(code.isNotEmpty()) { "CodeSnippet cannot be empty" }
        require(uploadedByUserId > 0) { "uploadedByUserId must be positive" }
    }

    fun embedToken(): String = "@snippet($markerId)"
}
