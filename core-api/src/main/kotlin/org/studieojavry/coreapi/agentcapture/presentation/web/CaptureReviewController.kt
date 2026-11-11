package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.application.usecase.DiscardCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.DiscardCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.GetCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.IdempotencyService
import org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.SolutionEdit
import org.studieojavry.coreapi.agentcapture.application.usecase.StepEdit
import org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListCapturesForReviewUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ArtifactResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ImportCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AttemptResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureDetailResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ImportCaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ReviewQueueResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.SolutionResponse
import java.util.UUID

/**
 * 사용자 검토 경로(일반 웹 인증). 에이전트가 봉인(READY_FOR_REVIEW)한 초안을 **사람이** 검토해
 * 기존 Case/Step/Solution 으로 반영(import)하거나 폐기(discard)한다.
 *
 * 에이전트 경로(`/api/v1/agent/…`, agent 토큰 전용)와 분리 — 여기선 세션 사용자(userId)만 신뢰한다.
 * 본인 소유 초안만 접근(아니면 404).
 */
@Tag(name = "capture-review", description = "자동 수집 초안의 사용자 검토·반영(import)·폐기 — 세션 사용자 전용.")
@RestController
@RequestMapping("/api/v1/captures")
class CaptureReviewController(
    private val listForReview: ListCapturesForReviewUseCase,
    private val getCapture: GetCaptureUseCase,
    private val importCapture: ImportCaptureUseCase,
    private val discardCapture: DiscardCaptureUseCase,
    private val idempotency: IdempotencyService,
) {

    @Operation(
        summary = "검토 큐 조회",
        description = "봉인된(READY_FOR_REVIEW) 본인 초안 목록(최근 갱신순). 반영/폐기 대기열.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping
    fun reviewQueue(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
    ): ReviewQueueResponse =
        ReviewQueueResponse(items = listForReview.invoke(userId).map(CaptureResponse::from))

    @Operation(
        summary = "초안 상세",
        description = "Attempt·Solution·근거 첨부 포함. 반영 전 검토 화면용. 본인 소유만(아니면 404).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
    )
    @GetMapping("/{captureUuid}")
    fun detail(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
    ): CaptureDetailResponse {
        val view = getCapture.invoke(captureUuid, userId)
        return CaptureDetailResponse(
            capture = CaptureResponse.from(view.capture),
            attempts = view.attempts.map(AttemptResponse::from),
            solutions = view.solutions.map(SolutionResponse::from),
            artifacts = view.artifacts.map(ArtifactResponse::from),
        )
    }

    @Operation(
        summary = "검토 확정 → 반영(import)",
        description = """
            봉인된 초안을 **신규 ErrorCase** 로 반영한다. 항상 PRIVATE, OPEN(자동 RESOLVED 안 함).
            Attempt→Step(결과→상태), SolutionCandidate→Solution(Step 참조)으로 매핑된다.
            멱등: 이미 반영됐으면 같은 케이스를 200 으로 반환. OPEN(미봉인)/DISCARDED → 409.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "반영됨(신규 케이스 생성)"),
        ApiResponse(responseCode = "200", description = "이미 반영됨(멱등)"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "봉인되지 않음 / 폐기됨 / revision 충돌 / 다른 선택·편집으로 재반영", content = [Content()]),
        ApiResponse(responseCode = "422", description = "미선택 편집 키 / 실행 안 한 검증을 PASSED 로 확정(UNEXECUTED_VERIFICATION_PASSED 등)", content = [Content()]),
    )
    @PostMapping("/{captureUuid}/import")
    fun import(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: ImportCaptureRequest,
    ): ResponseEntity<ImportCaptureResponse> {
        val outcome = idempotency.execute(
            scopeKey = "user:$userId",
            operation = "capture.import",
            key = idempotencyKey,
            requestPayload = request,
            responseType = ImportCaptureResponse::class.java,
            successStatus = 201,
        ) {
            val r = importCapture.invoke(
                ImportCaptureCommand(
                    captureUuid = captureUuid,
                    reviewerUserId = userId,
                    reviewedRevision = request.reviewedRevision,
                    selectedAttemptIds = request.selectedAttemptIds,
                    selectedSolutionIds = request.selectedSolutionIds,
                    selectedArtifactIds = request.selectedArtifactIds,
                    markResolved = request.markResolved,
                    editedTitle = request.editedTitle,
                    editedDescription = request.editedDescription,
                    stepEdits = request.stepEdits.mapValues { StepEdit(it.value.title, it.value.body, it.value.status) },
                    solutionEdits = request.solutionEdits.mapValues {
                        SolutionEdit(it.value.title, it.value.description, it.value.verification, it.value.clearVerification)
                    },
                    note = request.note,
                )
            )
            ImportCaptureResponse(captureUuid = captureUuid, caseId = r.caseId, alreadyImported = r.alreadyImported)
        }
        val status = if (outcome.replayed || outcome.body.alreadyImported) 200 else 201
        return ResponseEntity.status(status).body(outcome.body)
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }

    @Operation(
        summary = "초안 폐기(discard)",
        description = "검토 후 버린다(DISCARDED). 이미 폐기면 멱등, 이미 반영(IMPORTED)된 초안은 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "폐기됨/멱등"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "이미 반영됨", content = [Content()]),
    )
    @PostMapping("/{captureUuid}/discard")
    fun discard(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
    ): CaptureResponse =
        CaptureResponse.from(
            discardCapture.invoke(DiscardCaptureCommand(captureUuid = captureUuid, reviewerUserId = userId)),
        )
}
