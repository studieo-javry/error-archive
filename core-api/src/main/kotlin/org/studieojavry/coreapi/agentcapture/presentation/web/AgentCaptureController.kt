package org.studieojavry.coreapi.agentcapture.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
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
import org.studieojavry.coreapi.agentcapture.application.usecase.CompleteCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureCommand
import org.studieojavry.coreapi.agentcapture.application.usecase.CreateCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.GetCaptureUseCase
import org.studieojavry.coreapi.agentcapture.application.usecase.IdempotencyService
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
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.ArtifactResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.AttemptResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureDetailResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CaptureResponse
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CompleteCaptureRequest
import org.studieojavry.coreapi.agentcapture.presentation.web.dto.CreateCaptureRequest
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
    private val completeCapture: CompleteCaptureUseCase,
    private val uploadArtifact: UploadCaptureArtifactUseCase,
    private val idempotency: IdempotencyService,
) {

    @Operation(summary = "세션 등록", description = "동일 sessionUuid 재전송은 같은 세션을 재사용한다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록/재사용"),
        ApiResponse(responseCode = "400", description = "Idempotency-Key 누락/검증 실패", content = [Content()]),
        ApiResponse(responseCode = "409", description = "같은 키·다른 payload", content = [Content()]),
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
                        connectionId = UUID.fromString(principal.connectionId),
                        clientKind = request.clientKind,
                        clientVersion = request.clientVersion,
                        clientSessionId = request.clientSessionId,
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(summary = "OPEN 초안 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성/재생"),
        ApiResponse(responseCode = "400", description = "Idempotency-Key 누락", content = [Content()]),
        ApiResponse(responseCode = "409", description = "같은 키·다른 payload / 동시 경합", content = [Content()]),
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
                    )
                )
            )
        }
        return ResponseEntity.status(result.status).body(result.body)
    }

    @Operation(summary = "초안 조회·재개", description = "Attempt·Solution 포함. 본인 소유만(아니면 404).")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
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
        ApiResponse(responseCode = "409", description = "같은 clientArtifactId·다른 해시 / 봉인", content = [Content()]),
        ApiResponse(responseCode = "413", description = "파일/총량 한도 초과", content = [Content()]),
        ApiResponse(responseCode = "415", description = "허용되지 않은 형식(SVG/HTML/실행/압축)", content = [Content()]),
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
        ApiResponse(responseCode = "404", description = "없음/타인 소유", content = [Content()]),
        ApiResponse(responseCode = "409", description = "revision 충돌 / 이미 봉인", content = [Content()]),
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
        ApiResponse(responseCode = "409", description = "같은 키·다른 payload / 중복 clientAttemptId / 봉인", content = [Content()]),
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
        ApiResponse(responseCode = "404", description = "없음", content = [Content()]),
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
        ApiResponse(responseCode = "409", description = "중복 clientSolutionId / 봉인", content = [Content()]),
        ApiResponse(responseCode = "422", description = "존재하지 않는 Attempt 참조", content = [Content()]),
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

    @Operation(summary = "초안 봉인(READY_FOR_REVIEW)", description = "문제 해결 여부와 무관. Stop Hook 만으로 자동 호출하지 않는다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "봉인됨/재생"),
        ApiResponse(responseCode = "409", description = "revision 충돌 / 이미 봉인", content = [Content()]),
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

    companion object {
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
