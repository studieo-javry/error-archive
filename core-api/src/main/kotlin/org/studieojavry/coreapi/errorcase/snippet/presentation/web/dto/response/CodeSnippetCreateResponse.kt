package org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response

data class CodeSnippetCreateResponse(
    val markerId: String,
    val embedToken: String,
    val title: String?,
    val language: String,
    val caption: String?
)
