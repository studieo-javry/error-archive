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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import org.studieojavry.coreapi.agentcapture.application.usecase.DiscardCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.DiscardCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.GetCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.IdempotencyService
import org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.SolutionEdit
import org.studieojavry.coreapi.agentcapture.application.usecase.StepEdit
import org.studieojavry.coreapi.agentcapture.application.usecase.ImportCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListCaptureHistoryUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.CaptureMetricsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListCaptureOrphanEventsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListCaptureLinkedEventsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.AttachReviewEventsUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListCapturesForReviewUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RestoreCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.RestoreCaptureUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ArtifactResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ImportCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AttemptResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureDetailResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureHistoryItemResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureHistoryResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ImportCaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureMetricsResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ReviewQueueResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.SolutionResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentEventSummaryResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentEventListResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureOrphanEventsResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.LinkEventsRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.LinkEventsResponse
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import tools.jackson.databind.ObjectMapper
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
    private val listHistory: ListCaptureHistoryUseCase,
    private val getCapture: GetCaptureUseCase,
    private val importCapture: ImportCaptureUseCase,
    private val discardCapture: DiscardCaptureUseCase,
    private val restoreCapture: RestoreCaptureUseCase,
    private val captureMetrics: CaptureMetricsUseCase,
    private val listOrphanEvents: ListCaptureOrphanEventsUseCase,
    private val listLinkedEvents: ListCaptureLinkedEventsUseCase,
    private val attachReviewEvents: AttachReviewEventsUseCase,
    private val idempotency: IdempotencyService,
    private val objectMapper: ObjectMapper,
) {

    @Operation(
        summary = "검토 큐 조회 (cursor 페이지)",
        description = """
            봉인된(READY_FOR_REVIEW) 본인 초안 목록(최근 갱신순). 반영/폐기 대기열.
            keyset 페이징: `cursor` 생략 = 첫 페이지, 응답의 `nextCursor` 를 다음 `cursor` 로. `hasNext=false` 면 끝.
            `limit` 기본 20, 상한 100. 정렬 `updatedAt DESC, id DESC`.
        """,
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping
    fun reviewQueue(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "다음 페이지 cursor(이전 응답의 nextCursor)")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100)")
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): ReviewQueueResponse {
        val page = listForReview.invoke(userId, cursor, limit)
        return ReviewQueueResponse(
            items = page.items.map(CaptureResponse::from),
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    @Operation(
        summary = "자동기록 계측(퍼널)",
        description = """
            최근 windowDays(기본 30, 1~365) 내 생성된 본인 초안의 상태 퍼널. 자동기록의 품질 모니터링용.
            discardRate=폐기/검토완료(오생성 지표), adoptionRate=반영/검토완료. 검토완료(reviewed)=0 이면 두 비율은 null.
            성공률(자동 트리거 자체)은 모델 행동이라 서버가 관측하지 않는다(E2E 하네스로 측정).
        """,
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "성공"))
    @GetMapping("/metrics")
    fun metrics(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "집계 창(일). 기본 30, 1~365 로 클램프.")
        @RequestParam(required = false, defaultValue = "30") windowDays: Int,
    ): CaptureMetricsResponse {
        val m = captureMetrics.invoke(userId, windowDays)
        return CaptureMetricsResponse(
            windowDays = m.windowDays, since = m.since, created = m.created, open = m.open,
            readyForReview = m.readyForReview, imported = m.imported, discarded = m.discarded,
            reviewed = m.reviewed, discardRate = m.discardRate, adoptionRate = m.adoptionRate,
        )
    }

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
            // captureUuid 를 스코프에 포함 — 다른 Capture 에 같은 Idempotency-Key·본문을 써도 서로의 결과를 재생하지 않게.
            scopeKey = "user:$userId:capture:$captureUuid",
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
                    projectBindingId = request.projectBindingId,
                    clearProjectBinding = request.clearProjectBinding,
                    promoteSnippets = request.promoteSnippets,
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

    @Operation(
        summary = "검토 완료 이력 조회 (cursor 페이지)",
        description = """
            종단 상태(IMPORTED·DISCARDED) 본인 초안 목록(최근 갱신순). status 로 한 상태만 필터(없으면 둘 다).
            IMPORTED 항목은 caseId 로 반영된 Case 를 가리킨다.
            keyset 페이징: `cursor` 생략 = 첫 페이지, 응답의 `nextCursor` 를 다음 `cursor` 로. `hasNext=false` 면 끝.
            `limit` 기본 20, 상한 100. 정렬 `updatedAt DESC, id DESC`.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "status 가 IMPORTED/DISCARDED 가 아님", content = [Content()]),
    )
    @GetMapping("/history")
    fun history(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "IMPORTED 또는 DISCARDED (생략 시 둘 다)")
        @RequestParam(required = false) status: CaptureStatus?,
        @Parameter(description = "다음 페이지 cursor(이전 응답의 nextCursor)")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100)")
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): CaptureHistoryResponse {
        val page = listHistory.invoke(userId, status, cursor, limit)
        return CaptureHistoryResponse(
            items = page.items.map(CaptureHistoryItemResponse::from),
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    @Operation(
        summary = "폐기 복원(restore)",
        description = """
            폐기(DISCARDED)한 초안을 **폐기 직전 단계로** 되돌린다. 내용(Attempt·Solution·근거)은 그대로 복원.
            봉인(complete) 이력이 있으면 READY_FOR_REVIEW(검토 큐로 복귀), 아직 봉인 전이었으면 OPEN 으로 되돌아간다
            (미봉인 초안을 바로 READY 로 올리면 complete 의 근거 완료 검증을 우회하므로). OPEN 으로 복원되면 검토 큐·검토
            이력 어디에도 보이지 않고 에이전트가 이어서 봉인할 수 있는 상태가 된다. 복원된 실제 상태는 응답 status 로 확인.
            본인 소유만(아니면 404), DISCARDED 아닌 상태는 409.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "복원됨(폐기 직전 상태 = READY_FOR_REVIEW 또는 OPEN — 응답 status 참조)"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "DISCARDED 아님(복원 불가)", content = [Content()]),
    )
    @PostMapping("/{captureUuid}/restore")
    fun restore(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
    ): CaptureResponse =
        CaptureResponse.from(
            restoreCapture.invoke(RestoreCaptureCommand(captureUuid = captureUuid, reviewerUserId = userId)),
        )

    @Operation(
        summary = "봉인 후 고아 근거 조회 (리뷰 백스톱)",
        description = """
            봉인(READY_FOR_REVIEW) 후 뒤늦게 서버에 도착해 이 Capture 에 연결되지 못한, **같은 세션의
            미연결 이벤트**를 반환한다. 커넥터가 force 로 봉인했거나 미전송분이 늦게 반영된 경우의 근거 공백을
            리뷰에서 메우기 위한 후보. 이 Capture 가 생성된 세션 범위로만 좁힌다. 본인 소유만(아니면 404).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공(없으면 빈 목록)"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
    )
    @GetMapping("/{captureUuid}/orphan-events")
    fun orphanEvents(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
    ): CaptureOrphanEventsResponse =
        CaptureOrphanEventsResponse(items = listOrphanEvents.invoke(captureUuid, userId).map(::toSummary))

    @Operation(
        summary = "고아 근거를 이 Capture 에 연결 (리뷰 백스톱)",
        description = """
            리뷰에서 선택한 고아 이벤트를 이 Capture 에 연결한다(사람 경로라 봉인된 초안에도 허용 — READY_FOR_REVIEW 에서만).
            같은 세션·미연결 이벤트만 붙는다. linked=연결됨(멱등 포함), skipped=없음/타 Capture 소속/타 세션이라 제외.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "READY_FOR_REVIEW 아님(봉인 전/반영·폐기됨)", content = [Content()]),
    )
    @PostMapping("/{captureUuid}/orphan-events/link")
    fun linkOrphanEvents(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
        @Valid @RequestBody request: LinkEventsRequest,
    ): LinkEventsResponse {
        val r = attachReviewEvents.invoke(captureUuid, userId, request.clientEventIds)
        return LinkEventsResponse(linked = r.linked, skipped = r.skipped)
    }

    @Operation(
        summary = "이 Capture 에 연결된 수집 근거 조회 (cursor 페이지)",
        description = """
            이 Capture 에 연결된(LINKED) 수집 근거(Hook 자동수집 이벤트)를 최신순 조회한다. #205 의 '추가 근거' 연결 결과가
            검토 화면에서 실제로 되비춰지는 경로. 본인 소유만(아니면 404).
            keyset 페이징: `cursor` 생략 = 첫 페이지, 응답 `nextCursor` 를 다음 `cursor` 로. `hasNext=false` 면 끝. 정렬 id DESC.
            payload 는 마스킹된 원본으로 인라인 반환(소유자 전용 — 발행/공유 노출은 별도 정책).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공(없으면 빈 목록)"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
    )
    @GetMapping("/{captureUuid}/events")
    fun linkedEvents(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @PathVariable captureUuid: UUID,
        @Parameter(description = "keyset cursor — 이전 응답의 nextCursor. 이 값 미만의 더 오래된 이벤트를 조회.")
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): AgentEventListResponse {
        val page = listLinkedEvents.invoke(captureUuid, userId, cursor, limit.coerceIn(1, 100))
        return AgentEventListResponse(items = page.items.map(::toSummary), nextCursor = page.nextCursor, hasNext = page.hasNext)
    }

    private fun toSummary(e: CaptureEvent): AgentEventSummaryResponse = AgentEventSummaryResponse(
        clientEventId = e.clientEventId,
        eventType = e.eventType,
        sessionId = e.sessionId,
        observedAt = e.observedAt,
        toolCallId = e.toolCallId,
        truncated = e.truncated,
        status = e.status.name,
        redactedPayload = objectMapper.readTree(e.redactedPayload),
    )
}
