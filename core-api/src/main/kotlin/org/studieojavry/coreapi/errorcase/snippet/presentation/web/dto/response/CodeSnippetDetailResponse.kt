package org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response

import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet

/** 스니펫 단건 응답(내용 포함). 수정 결과 반환 등에 사용. */
data class CodeSnippetDetailResponse(
    val markerId: String,
    val embedToken: String,
    val title: String,
    val language: String,
    val filePathOrClass: String?,
    val lineRange: String?,
    val caption: String?,
    val code: String,
    val errorCaseId: Long?
) {
    companion object {
        fun from(s: CodeSnippet) = CodeSnippetDetailResponse(
            markerId = s.markerId,
            embedToken = s.embedToken(),
            title = s.title,
            language = s.language,
            filePathOrClass = s.filePathOrClass,
            lineRange = s.lineRange,
            caption = s.caption,
            code = s.code,
            errorCaseId = s.errorCaseId
        )
    }
}