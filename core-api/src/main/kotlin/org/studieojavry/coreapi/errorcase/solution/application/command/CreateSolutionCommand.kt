package org.studieojavry.coreapi.errorcase.solution.application.command

data class CreateSolutionCommand(
    val errorCaseId: Long,
    val authorUserId: Long,
    val title: String,
    val stepIds: List<Long>,
)
