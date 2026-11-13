package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.studieojavry.coreapi.agentcapture.application.usecase.AddAttemptCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.AddAttemptUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.AddSolutionCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.AddSolutionUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.UpdateSolutionCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.UpdateSolutionUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.CompleteCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.GetCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.IdempotencyService
import org.studieojavry.coreapi.agentcapture.application.usecase.LinkEventsToCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.ListOpenCapturesUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.PatchCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.PatchCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterCaptureSessionUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.RegisterSessionCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.UpdateAttemptCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.UpdateAttemptUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.UploadArtifactCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.UploadCaptureArtifactUseCase
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AddAttemptRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AddSolutionRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.UpdateSolutionRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AgentErrorResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ArtifactResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AttemptResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureDetailResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CompleteCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CreateCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.LinkEventsRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.LinkEventsResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.OpenCaptureListResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.PatchCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.RegisterSessionRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.SessionResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.SolutionResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.UpdateAttemptRequest
import java.util.UUID

/**
 * 로컬 커넥터(MCP) 전용 초안 수집 경로. 모든 생성/봉인은 Idempotency-Key 를 요구한다.
 * scope: 읽기=capture:read, 쓰기=capture:write (경로 제한 + application 권한 이중 검사).
 */
@Tag(name = "agent-capture", description = "자동 수집 초안(Capture/Attempt/Solution) — agent 토큰 전용.")
@RestController
@RequestMapping("/api/v1/agent")
class AgentCaptureController(
    private val registerSession: RegisterCaptureSessionUseCase,
    private val createCapture: CreateCaptureUseCase,
    private val getCapture: GetCaptureUseCase,
    private val patchCapture: PatchCaptureUseCase,
    private val addAttempt: AddAttemptUseCase,
    private val updateAttempt: UpdateAttemptUseCase,
    private val addSolution: AddSolutionUseCase,
    private val updateSolution: UpdateSolutionUseCase,
    private val completeCapture: CompleteCaptureUseCase,
    private val uploadArtifact: UploadCaptureArtifactUseCase,
    private val linkEvents: LinkEventsToCaptureUseCase,
    private val listOpenCaptures: ListOpenCapturesUseCase,
    private val idempotency: IdempotencyService,
) {

    @Operation(summary = "세션 등록", description = "동일 sessionUuid 재전송은 같은 세션을 재사용한다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록/재사용"),
        ApiResponse(
            responseCode = "400", description = "Idempotency-Key 누락/검증 실패 (code=INVALID_REQUEST)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "같은 키·다른 payload (code=IDEMPOTENCY_CONFLICT)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/sessions")
    fun registerSession(
        principal: AgentPrincipal,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: RegisterSessionRequest,
    ): ResponseEntity<SessionResponse> {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val result = idempotency.execute(
            scopeKey = principal.connectionId, operation = "session.register",
            key = idempotencyKey, requestPayload = request,
            responseType = SessionResponse::class.java, successStatus = 201,
        ) {
            SessionResponse.from(
                registerSession.invoke(
                    RegisterSessionCommand(
                        sessionUuid = request.sessionUuid,
                        ownerUserId = principal.userId,
                        connectionId = UUID.fromString(principal.connectionId),
                        clientKind = request.clientKind,
                        clientVersion = request.clientVersion,
                        clientSessionId = request.clientSessionId,
                        projectBindingId = request.projectBindingId,
                        projectKey = request.projectKey,
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(summary = "OPEN 초안 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성/재생"),
        ApiResponse(
            responseCode = "400", description = "Idempotency-Key 누락 (code=INVALID_REQUEST)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "같은 키·다른 payload / 동시 경합 (code=IDEMPOTENCY_CONFLICT·CONCURRENT_REQUEST)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/captures")
    fun create(
        principal: AgentPrincipal,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: CreateCaptureRequest,
    ): ResponseEntity<CaptureResponse> {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val result = idempotency.execute(
            scopeKey = principal.connectionId, operation = "capture.create",
            key = idempotencyKey, requestPayload = request,
            responseType = CaptureResponse::class.java, successStatus = 201,
        ) {
            CaptureResponse.from(
                createCapture.invoke(
                    CreateCaptureCommand(
                        captureUuid = request.captureUuid,
                        ownerUserId = principal.userId,
                        connectionId = UUID.fromString(principal.connectionId),
                        title = request.title,
                        project = request.project,
                        content = request.content,
                        sessionUuid = request.sessionUuid,
                        projectBindingId = request.projectBindingId,
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(
        summary = "진행 중(OPEN) Capture 후보 조회",
        description = """
            현재 연결(install)이 만든 OPEN(미봉인) Capture 를 최근 갱신순으로 반환한다. `sessionUuid` 를 주면
            그 세션(현재 연결 소유)에 연결된 것만 좁혀 보여준다. AI 가 지금 다루는 문제가 기존 진행 중 Capture 와
            같은 버그인지 판단해, 같으면 그 captureUuid 를 재사용(Attempt 누적)하고 다르면 새로 생성하도록(한 버그=한
            Capture, 중복 난립 방지) 돕는 조회다. READY_FOR_REVIEW·IMPORTED·DISCARDED 는 포함하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공(빈 목록 가능)"),
    )
    @GetMapping("/captures")
    fun listOpen(
        principal: AgentPrincipal,
        @Parameter(description = "이 세션에 연결된 Capture 로 좁히기(선택). 현재 연결에서 등록한 세션만 유효.")
        @RequestParam(required = false) sessionUuid: UUID?,
    ): OpenCaptureListResponse {
        principal.requireScope(AgentScopes.CAPTURE_READ)
        val items = listOpenCaptures
            .invoke(UUID.fromString(principal.connectionId), sessionUuid)
            .map(CaptureResponse::from)
        return OpenCaptureListResponse(items = items)
    }

    @Operation(summary = "초안 조회·재개", description = "Attempt·Solution 포함. 본인 소유만(아니면 404).")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @GetMapping("/captures/{captureUuid}")
    fun get(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
    ): CaptureDetailResponse {
        principal.requireScope(AgentScopes.CAPTURE_READ)
        val view = getCapture.invoke(captureUuid, principal.userId)
        return CaptureDetailResponse(
            capture = CaptureResponse.from(view.capture),
            attempts = view.attempts.map(AttemptResponse::from),
            solutions = view.solutions.map(SolutionResponse::from),
            artifacts = view.artifacts.map(ArtifactResponse::from),
        )
    }

    @Operation(
        summary = "근거 첨부 업로드(multipart)",
        description = """
            로컬 파일을 실제로 전송해 Capture 근거로 저장한다(로컬 경로 문자열만 보내는 것 금지).
            허용: PNG/JPEG/WebP, UTF-8 텍스트·로그·diff, JSON. 파일당 10 MiB, Capture당 20개·총 50 MiB.
            멱등: 같은 clientArtifactId·같은 내용은 기존 첨부 반환(200), 다른 내용은 409. OPEN 에서만.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "업로드됨"),
        ApiResponse(responseCode = "200", description = "동일 첨부 재업로드(멱등)"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "같은 clientArtifactId·다른 해시 / 봉인 (code=ARTIFACT_HASH_MISMATCH·CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "413", description = "파일/총량 한도 초과 (code=ARTIFACT_TOO_LARGE)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "415", description = "허용되지 않은 형식(SVG/HTML/실행/압축) (code=UNSUPPORTED_ARTIFACT)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/captures/{captureUuid}/artifacts", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadArtifact(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @RequestParam("clientArtifactId") clientArtifactId: String,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<ArtifactResponse> {
        principal.requireScope(AgentScopes.ARTIFACT_WRITE)
        val contentType = file.contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val outcome = uploadArtifact.invoke(
            UploadArtifactCommand(
                captureUuid = captureUuid,
                ownerUserId = principal.userId,
                clientArtifactId = clientArtifactId,
                declaredContentType = contentType,
                fileName = file.originalFilename?.takeIf { it.isNotBlank() },
                bytes = file.bytes,
            )
        )
        val status = if (outcome.deduplicated) 200 else 201
        return ResponseEntity.status(status).body(ArtifactResponse.from(outcome.artifact))
    }

    @Operation(summary = "OPEN 초안 기본 정보 수정", description = "expectedRevision 불일치 → 409. title/project/content 전체 교체.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정됨"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "revision 충돌 / 이미 봉인 (code=REVISION_CONFLICT·CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PatchMapping("/captures/{captureUuid}")
    fun patch(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @Valid @RequestBody request: PatchCaptureRequest,
    ): CaptureResponse {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        return CaptureResponse.from(
            patchCapture.invoke(
                PatchCaptureCommand(
                    captureUuid = captureUuid,
                    ownerUserId = principal.userId,
                    expectedRevision = request.expectedRevision,
                    title = request.title,
                    project = request.project,
                    content = request.content,
                )
            )
        )
    }

    @Operation(summary = "Attempt 추가")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가/재생"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "같은 키·다른 payload / 중복 clientAttemptId / 봉인 (code=IDEMPOTENCY_CONFLICT·CONCURRENT_REQUEST·CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/captures/{captureUuid}/attempts")
    fun addAttempt(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: AddAttemptRequest,
    ): ResponseEntity<AttemptResponse> {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val result = idempotency.execute(
            scopeKey = principal.connectionId, operation = "attempt.add",
            key = idempotencyKey, requestPayload = request,
            responseType = AttemptResponse::class.java, successStatus = 201,
        ) {
            AttemptResponse.from(
                addAttempt.invoke(
                    AddAttemptCommand(
                        captureUuid = captureUuid,
                        ownerUserId = principal.userId,
                        clientAttemptId = request.clientAttemptId,
                        result = request.result,
                        attemptType = request.attemptType,
                        detail = request.detail,
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(summary = "Attempt 갱신", description = "모르는 clientAttemptId → 404. 봉인 후 → 409.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신됨"),
        ApiResponse(
            responseCode = "404", description = "없는 clientAttemptId / 없음·타인 소유 Capture (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "봉인 후 수정 시도 (code=CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PatchMapping("/captures/{captureUuid}/attempts/{clientAttemptId}")
    fun updateAttempt(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @PathVariable clientAttemptId: String,
        @Valid @RequestBody request: UpdateAttemptRequest,
    ): AttemptResponse {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        return AttemptResponse.from(
            updateAttempt.invoke(
                UpdateAttemptCommand(
                    captureUuid = captureUuid,
                    ownerUserId = principal.userId,
                    clientAttemptId = clientAttemptId,
                    result = request.result,
                    attemptType = request.attemptType,
                    detail = request.detail,
                )
            )
        )
    }

    @Operation(summary = "Solution 후보 추가", description = "content.clientAttemptIds 는 같은 Capture 의 Attempt 여야 한다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가/재생"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "중복 clientSolutionId / 봉인 (code=IDEMPOTENCY_CONFLICT·CONCURRENT_REQUEST·CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "422",
            description = "없는 Attempt 참조 / 참조 0개 / 미실행 PASSED·근거 없음 " +
                "(code=UNKNOWN_ATTEMPT_REFERENCE·SOLUTION_WITHOUT_ATTEMPT·" +
                "UNEXECUTED_VERIFICATION_PASSED·VERIFICATION_EVIDENCE_REQUIRED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/captures/{captureUuid}/solutions")
    fun addSolution(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody request: AddSolutionRequest,
    ): ResponseEntity<SolutionResponse> {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val result = idempotency.execute(
            scopeKey = principal.connectionId, operation = "solution.add",
            key = idempotencyKey, requestPayload = request,
            responseType = SolutionResponse::class.java, successStatus = 201,
        ) {
            SolutionResponse.from(
                addSolution.invoke(
                    AddSolutionCommand(
                        captureUuid = captureUuid,
                        ownerUserId = principal.userId,
                        clientSolutionId = request.clientSolutionId,
                        title = request.title,
                        content = request.content,
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(
        summary = "Solution 후보 수정",
        description = "통째 교체(봉인 전 정정). 모르는 clientSolutionId → 422. 봉인 후 → 409. " +
            "PASSED 검증은 executed=true + 근거 필요(수집 규칙과 일관).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정됨"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 Capture (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "봉인 후 수정 시도 (code=CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "422",
            description = "모르는 clientSolutionId / 없는 Attempt 참조 / 참조 0개 / 미실행 PASSED·근거 없음 " +
                "(code=UNKNOWN_SOLUTION_REFERENCE·UNKNOWN_ATTEMPT_REFERENCE·SOLUTION_WITHOUT_ATTEMPT·" +
                "UNEXECUTED_VERIFICATION_PASSED·VERIFICATION_EVIDENCE_REQUIRED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PatchMapping("/captures/{captureUuid}/solutions/{clientSolutionId}")
    fun updateSolution(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @PathVariable clientSolutionId: String,
        @Valid @RequestBody request: UpdateSolutionRequest,
    ): SolutionResponse {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        return SolutionResponse.from(
            updateSolution.invoke(
                UpdateSolutionCommand(
                    captureUuid = captureUuid,
                    ownerUserId = principal.userId,
                    clientSolutionId = clientSolutionId,
                    title = request.title,
                    content = request.content,
                )
            )
        )
    }

    @Operation(summary = "초안 봉인(READY_FOR_REVIEW)", description = "문제 해결 여부와 무관. Stop Hook 만으로 자동 호출하지 않는다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "봉인됨/재생"),
        ApiResponse(
            responseCode = "404", description = "없음/타인 소유 (code=CAPTURE_NOT_FOUND)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409", description = "revision 충돌 / 이미 봉인 (code=REVISION_CONFLICT·CAPTURE_SEALED)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "422",
            description = "근거 미준비 — Attempt 가 참조한 evidenceRef 중 업로드 안 된 것이 있음 " +
                "(code=EVIDENCE_NOT_READY, body.missingEvidenceRefs 에 누락 ref 목록)",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = AgentErrorResponse::class))],
        ),
    )
    @PostMapping("/captures/{captureUuid}/complete")
    fun complete(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @RequestHeader(IDEMPOTENCY_KEY) idempotencyKey: String,
        @Valid @RequestBody(required = false) request: CompleteCaptureRequest?,
    ): ResponseEntity<CaptureResponse> {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val expectedRevision = request?.expectedRevision
        val result = idempotency.execute(
            scopeKey = principal.connectionId, operation = "capture.complete",
            key = idempotencyKey, requestPayload = (expectedRevision ?: "null"),
            responseType = CaptureResponse::class.java, successStatus = 200,
        ) {
            CaptureResponse.from(completeCapture.invoke(captureUuid, principal.userId, expectedRevision))
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(
        summary = "이벤트 → 초안 연결",
        description = "미분류 Hook 이벤트(clientEventId)를 OPEN 초안에 연결한다. 봉인/반영 초안엔 연결 불가(409). " +
            "없거나 다른 초안에 묶인 이벤트는 가로채지 않고 skipped 로 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "연결 결과(linked/skipped)"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "봉인/반영된 초안", content = [Content()]),
    )
    @PostMapping("/captures/{captureUuid}/events")
    fun linkEvents(
        principal: AgentPrincipal,
        @PathVariable captureUuid: UUID,
        @Valid @RequestBody request: LinkEventsRequest,
    ): LinkEventsResponse {
        principal.requireScope(AgentScopes.CAPTURE_WRITE)
        val result = linkEvents.invoke(
            captureUuid = captureUuid,
            ownerUserId = principal.userId,
            connectionId = UUID.fromString(principal.connectionId),
            clientEventIds = request.clientEventIds,
        )
        return LinkEventsResponse(linked = result.linked, skipped = result.skipped)
    }

    companion object {
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
