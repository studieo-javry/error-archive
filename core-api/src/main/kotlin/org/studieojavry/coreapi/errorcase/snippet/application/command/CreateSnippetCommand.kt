package org.studieojavry.coreapi.errorcase.snippet.application.command

data class CreateSnippetCommand(
    val uploaderUserId: Long,
    val title: String?,
    val language: String,
    val filePathOrClass: String?,
    val lineRange: String?,
    val caption: String?,
    val code: String
)
