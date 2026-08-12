package org.studieojavry.coreapi.errorcase.step.application.command

import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus

/** step 생성 — title/status 는 필수, attemptType(자유 String)/body/insight 는 선택. */
data class CreateStepCommand(
    val errorCaseId: Long,
    val authorUserId: Long,
    val title: String,
    val status: StepStatus,
    val attemptType: String?,
    val body: String?,
    val insight: String?,
)

/** step 부분 수정. null=유지. attemptType 비우려면 clearAttemptType=true. */
data class UpdateStepCommand(
    val stepId: Long,
    val requesterUserId: Long,
    val title: String?,
    val status: StepStatus?,
    val attemptType: String?,
    val clearAttemptType: Boolean,
    val body: String?,
    val insight: String?,
)
