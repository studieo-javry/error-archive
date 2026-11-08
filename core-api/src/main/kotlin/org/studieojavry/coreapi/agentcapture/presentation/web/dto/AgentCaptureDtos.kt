package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptResult
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import java.time.Instant
import java.util.UUID

// ── 요청 ─────────────────────────────────────────────────────────────────────
// 구조화 서사(CaptureContent/AttemptDetail/SolutionContent)는 도메인 값 객체를 그대로 JSON 스키마로 쓴다.

@Schema(description = "세션 등록. 동일 sessionUuid 재전송은 같은 세션을 재사용한다.")
data class RegisterSessionRequest(
    val sessionUuid: UUID,
    @field:NotBlank val clientKind: String,
    val clientVersion: String? = null,
    @field:NotBlank val clientSessionId: String,
)

@Schema(description = "OPEN 초안 생성. captureUuid 는 클라이언트가 부여하며 재전송 시 변경하지 않는다.")
data class CreateCaptureRequest(
    val captureUuid: UUID,
    val title: String? = null,
    val project: String? = null,
    val content: CaptureContent = CaptureContent.EMPTY,
    @Schema(description = "이 초안을 이을 세션(선택). 등록된 세션이면 연결한다.")
    val sessionUuid: UUID? = null,
)

@Schema(description = "OPEN 초안 기본 정보 교체(title/project/content 전체 교체).")
data class PatchCaptureRequest(
    val expectedRevision: Int? = null,
    val title: String? = null,
    val project: String? = null,
    val content: CaptureContent = CaptureContent.EMPTY,
)

@Schema(description = "Attempt 추가/갱신. clientAttemptId 는 같은 Capture 안에서 유일.")
data class AddAttemptRequest(
    @field:NotBlank val clientAttemptId: String,
    val result: AttemptResult,
    val attemptType: String? = null,
    val detail: AttemptDetail,
)

@Schema(description = "Attempt 갱신(clientAttemptId 는 경로).")
data class UpdateAttemptRequest(
    val result: AttemptResult,
    val attemptType: String? = null,
    val detail: AttemptDetail,
)

@Schema(description = "Solution 후보 추가. content.clientAttemptIds 는 같은 Capture 의 Attempt 여야 한다.")
data class AddSolutionRequest(
    @field:NotBlank val clientSolutionId: String,
    @field:NotBlank val title: String,
    val content: SolutionContent,
)

@Schema(description = "봉인 요청(expectedRevision 로 낙관 검사).")
data class CompleteCaptureRequest(
    val expectedRevision: Int? = null,
)

// ── 응답 ─────────────────────────────────────────────────────────────────────

@Schema(description = "등록된 세션.")
data class SessionResponse(
    val sessionUuid: UUID,
    val connectionId: UUID,
    val clientKind: String,
    val clientVersion: String?,
    val clientSessionId: String,
    val startedAt: Instant,
) {
    companion object {
        fun from(s: CaptureSession) = SessionResponse(
            sessionUuid = s.sessionUuid,
            connectionId = s.connectionId,
            clientKind = s.clientKind,
            clientVersion = s.clientVersion,
            clientSessionId = s.clientSessionId,
            startedAt = s.startedAt,
        )
    }
}

@Schema(description = "초안 요약(revision 포함 — 이후 편집/봉인의 expectedRevision 에 사용).")
data class CaptureResponse(
    val captureUuid: UUID,
    val status: String,
    val revision: Int,
    val title: String?,
    val project: String?,
    val content: CaptureContent,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
) {
    companion object {
        fun from(c: AgentCapture) = CaptureResponse(
            captureUuid = c.captureUuid,
            status = c.status.name,
            revision = c.revision,
            title = c.title,
            project = c.project,
            content = c.content,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            completedAt = c.completedAt,
        )
    }
}

@Schema(description = "Attempt.")
data class AttemptResponse(
    val clientAttemptId: String,
    val orderIndex: Int,
    val result: AttemptResult,
    val attemptType: String?,
    val detail: AttemptDetail,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(a: CaptureAttempt) = AttemptResponse(
            clientAttemptId = a.clientAttemptId,
            orderIndex = a.orderIndex,
            result = a.result,
            attemptType = a.attemptType,
            detail = a.detail,
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
        )
    }
}

@Schema(description = "Solution 후보.")
data class SolutionResponse(
    val clientSolutionId: String,
    val title: String,
    val content: SolutionContent,
    val createdAt: Instant,
) {
    companion object {
        fun from(s: CaptureSolutionCandidate) = SolutionResponse(
            clientSolutionId = s.clientSolutionId,
            title = s.title,
            content = s.content,
            createdAt = s.createdAt,
        )
    }
}

@Schema(description = "초안 상세(요약 + Attempt·Solution 목록).")
data class CaptureDetailResponse(
    val capture: CaptureResponse,
    val attempts: List<AttemptResponse>,
    val solutions: List<SolutionResponse>,
)
