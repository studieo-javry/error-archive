package org.studieojavry.coreapi.errorcase.step.presentation.web.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.LocalDateTime

data class StepResponse(
    val id: Long,
    val errorCaseId: Long,
    val authorUserId: Long,
    val orderIndex: Int,
    val title: String,
    val status: StepStatus,
    val attemptType: String?,
    val body: String?,
    val insight: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(s: Step) = StepResponse(
            id = s.id!!, errorCaseId = s.errorCaseId, authorUserId = s.authorUserId,
            orderIndex = s.orderIndex, title = s.title, status = s.status,
            attemptType = s.attemptType, body = s.body, insight = s.insight,
            createdAt = s.createdAt, updatedAt = s.updatedAt,
        )
    }
}

@Schema(description = "Step 생성 응답. `suggestResolve=true` 면 SPA 가 '해결됨으로 표시할까요?' 모달을 보여주고, 동의 시 PATCH /error-cases/{id} { status: RESOLVED } 호출.")
data class CreateStepResponse(
    val step: StepResponse,
    val caseStatus: ErrorCaseStatus,
    val suggestResolve: Boolean,
)

@Schema(description = "Step 수정 응답. step.status 를 RESOLVED 로 바꿨고 케이스가 IN_PROGRESS 면 `suggestResolve=true` — SPA 가 케이스 RESOLVED 전환 모달 노출.")
data class UpdateStepResponse(
    val step: StepResponse,
    val caseStatus: ErrorCaseStatus,
    val suggestResolve: Boolean,
)

@Schema(description = "Step 시도 분류 카탈로그 항목. `isSystem=true` 면 모든 사용자 공통, false 면 본인 커스텀.")
data class StepAttemptTypeResponse(
    val name: String,
    val isSystem: Boolean,
)
