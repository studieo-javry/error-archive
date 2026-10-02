package org.studieojavry.coreapi.errorcase.case.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentPresigner
import org.studieojavry.coreapi.errorcase.attachment.config.PresignTtlProperties
import org.studieojavry.coreapi.errorcase.case.application.command.CreateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.case.application.command.UpdateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.case.application.usecase.AddCaseToWatchlistUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.AddErrorCaseTagUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.CountMyCasesByStatusUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.CreateErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.DeleteErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.application.usecase.IdempotencyConflictException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseDeleteForbiddenException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseLinkException
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseNotFoundException
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetCaseMeTooUseCase
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.GetErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.ListErrorCasesUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.MarkCaseMeTooUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.RegisterCaseViewUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.RemoveCaseFromWatchlistUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.RemoveErrorCaseTagUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.SearchPublicErrorCasesUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.UnmarkCaseMeTooUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.UpdateErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.application.usecase.WorkspaceAccessDeniedException
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request.CreateErrorCaseRequest
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request.UpdateErrorCaseRequest
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.CreateErrorCaseResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorCaseDetailResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorCaseListResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.ErrorCaseSummaryResponse
import org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.MeTooResponse

@Tag(
    name = "error-cases",
    description = "에러 사례 CRUD/목록. 본문·메타·스냅샷을 가진 사례에 스니펫·첨부를 markerId 로 연결한다."
)
@RestController
@RequestMapping("/api/v1/error-cases")
class ErrorCaseController(
    private val createErrorCaseUseCase: CreateErrorCaseUseCase,
    private val getErrorCaseUseCase: GetErrorCaseUseCase,
    private val updateErrorCaseUseCase: UpdateErrorCaseUseCase,
    private val deleteErrorCaseUseCase: DeleteErrorCaseUseCase,
    private val listErrorCasesUseCase: ListErrorCasesUseCase,
    private val searchPublicErrorCasesUseCase: SearchPublicErrorCasesUseCase,
    private val addErrorCaseTagUseCase: AddErrorCaseTagUseCase,
    private val removeErrorCaseTagUseCase: RemoveErrorCaseTagUseCase,
    private val markCaseMeTooUseCase: MarkCaseMeTooUseCase,
    private val unmarkCaseMeTooUseCase: UnmarkCaseMeTooUseCase,
    private val getCaseMeTooUseCase: GetCaseMeTooUseCase,
    private val registerCaseViewUseCase: RegisterCaseViewUseCase,
    private val addCaseToWatchlistUseCase: AddCaseToWatchlistUseCase,
    private val removeCaseFromWatchlistUseCase: RemoveCaseFromWatchlistUseCase,
    private val countMyCasesByStatusUseCase: CountMyCasesByStatusUseCase,
    private val caseWatchlistRepository: CaseWatchlistRepositoryPort,
    private val attachmentPresigner: AttachmentPresigner,
    private val presignTtl: PresignTtlProperties,
) {

    /**
     * 가시성별 TTL 로 첨부 objectKey → (inline URL, download URL) presign.
     * 상세 조회는 이미 read 권한을 통과한 뒤라(가시성 검사 완료) 이 시점 발급이 안전하다.
     */
    private fun attachmentUrlResolver(visibility: Visibility) =
        ErrorCaseDetailResponse.AttachmentUrlResolver { objectKey ->
            val ttl = if (visibility == Visibility.PUBLIC)
                presignTtl.publicTtl else presignTtl.restrictedTtl
            attachmentPresigner.viewUrl(objectKey, ttl) to attachmentPresigner.downloadUrl(objectKey, ttl)
        }

    @Operation(
        summary = "에러 케이스 생성",
        description = """
            새 에러 사례를 등록한다.

            **언제 사용**
            - 사용자가 에러를 기록하고 싶을 때(개인 또는 워크스페이스 스코프).
            - 스니펫/첨부는 **먼저 `POST /error-snippets`·`POST /error-attachments` 로 독립 생성**해서 markerId 를 받은 뒤, 그 markerId 들을 본 요청 `snippetMarkerIds`/`attachmentMarkerIds` 로 전달해 연결한다.

            **권한**
            - `workspaceId` 지정 시: 그 워크스페이스의 **WRITE 이상** 역할 필요(없거나 READ 면 403).
            - `workspaceId` 미지정 시: 본인 개인 케이스로 생성.

            **검증**
            - `paste` 에서 예외 클래스/메시지/스택트레이스를 추출, fingerprint(SHA-256) 자동 생성.
            - `snippetMarkerIds`/`attachmentMarkerIds`: **존재·소유자 == 요청자·아직 미연결** 모두 통과해야 함(위반 400).
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201", description = "생성됨",
            content = [Content(examples = [ExampleObject(value = """
                {"id":15,"title":"NPE in OrderService","status":"OPEN","fingerprint":"a3f1...","snippetMarkerIds":["7a7d35e9"],"attachmentMarkerIds":[],"createdAt":"2026-05-27T19:42:00"}
            """)])]
        ),
        ApiResponse(responseCode = "200", description = "멱등 재생 — 같은 Idempotency-Key+같은 본문 재요청은 최초 케이스를 그대로 반환(응답 헤더 `Idempotent-Replayed: true`)", content = [Content()]),
        ApiResponse(
            responseCode = "400", description = "잘못된 입력 (검증 실패 / 미존재 marker / 타인 소유 marker / 이미 연결된 marker)",
            content = [Content(examples = [ExampleObject(value = """
                {"type":"about:blank","title":"Bad Request","status":400,"detail":"snippet(s) not owned by requester: [7a7d35e9]","instance":"/api/v1/error-cases"}
            """)])]
        ),
        ApiResponse(responseCode = "401", description = "인증 실패(X-Internal-Auth 없음/위조/만료)", content = [Content()]),
        ApiResponse(
            responseCode = "403", description = "워크스페이스 비멤버 또는 READ 권한(쓰기 불가)",
            content = [Content(examples = [ExampleObject(value = """
                {"type":"about:blank","title":"Forbidden","status":403,"detail":"WRITE role required to create an error case in workspace 1 (current=READ)","instance":"/api/v1/error-cases"}
            """)])]
        ),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 를 다른 본문으로 재사용 / 동시 중복 요청 경합", content = [Content()])
    )
    @PostMapping
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "멱등 키(선택). 클라이언트 생성 UUID 권장 — 같은 키로 온 재요청(더블클릭·재시도)은 최초 케이스로 재생하여 중복 생성을 막는다. 재시도 시 같은 키를 재사용해야 한다.")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateErrorCaseRequest
    ): ResponseEntity<CreateErrorCaseResponse> {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && key.length > IDEMPOTENCY_KEY_MAX) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long (max $IDEMPOTENCY_KEY_MAX)")
        }
        val requestHash = key?.let { request.canonicalHash() }

        val result = try {
            createErrorCaseUseCase.invoke(
                CreateErrorCaseCommand(
                    userId = userId,
                    title = request.title,
                    project = request.project,
                    paste = request.paste,
                    description = request.description,
                    snippetMarkerIds = request.snippetMarkerIds,
                    attachmentMarkerIds = request.attachmentMarkerIds,
                    workspaceId = request.workspaceId,
                    tags = request.tags,
                    occurredAt = request.occurredAt,
                    visibility = request.visibility,
                ),
                idempotencyKey = key,
                requestHash = requestHash,
            )
        } catch (e: WorkspaceAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: ErrorCaseLinkException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IdempotencyConflictException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }

        val body = CreateErrorCaseResponse(
            id = result.id,
            title = result.title,
            status = result.status,
            fingerprint = result.fingerprint,
            snippetMarkerIds = result.snippetMarkerIds,
            attachmentMarkerIds = result.attachmentMarkerIds,
            createdAt = result.createdAt
        )
        // 멱등 재생이면 200, 신규 생성이면 201. 클라이언트가 구분할 수 있게 헤더도 노출.
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status)
            .header("Idempotent-Replayed", result.replayed.toString())
            .body(body)
    }

    /**
     * 요청 본문의 안정적 해시 — 같은 Idempotency-Key 로 다른 payload 재사용을 탐지.
     * 필드/배열 순서에 무관하도록 정렬·정규화한 canonical 문자열을 SHA-256.
     */
    private fun CreateErrorCaseRequest.canonicalHash(): String {
        val canonical = listOf(
            title,
            project.orEmpty(),
            paste.orEmpty(),
            description.orEmpty(),
            snippetMarkerIds.sorted().joinToString(","),
            attachmentMarkerIds.sorted().joinToString(","),
            workspaceId?.toString().orEmpty(),
            tags?.map { it.trim().lowercase() }?.sorted()?.joinToString(",").orEmpty(),
            occurredAt?.toString().orEmpty(),
            visibility.name,
        ).joinToString(" ")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Operation(
        summary = "에러 케이스 목록 조회 (cursor 무한스크롤)",
        description = """
            keyset(cursor) 기반 목록. `createdAt DESC, id DESC` 정렬.

            **스코프**
            - `workspaceId` 지정: 그 워크스페이스 멤버(READ+)면 워크스페이스 전체.
            - `workspaceId` 미지정: 요청자 본인 소유 케이스.

            **페이징**
            - 첫 페이지: `cursor` 생략.
            - 다음 페이지: 이전 응답의 `nextCursor` 를 그대로 `cursor` 로 전달. `hasNext=false` 면 끝.
            - `size` 기본 20, 상한 100.

            **필터**(선택, 모두 AND)
            - `status` — `OPEN`/`IN_PROGRESS`/`RESOLVED` 등(`ErrorCaseStatus`). 잘못된 값 → 400.
            - `fingerprint` — 동일 지문(중복 사례 묶어보기).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "잘못된 status 값", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "워크스페이스 비멤버", content = [Content()])
    )
    @GetMapping
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "워크스페이스 ID — 지정 시 그 워크스페이스 전체, 미지정 시 본인 케이스") @RequestParam(required = false) workspaceId: Long?,
        @Parameter(description = "케이스 상태 (OPEN/IN_PROGRESS/RESOLVED 등)", example = "OPEN") @RequestParam(required = false) status: String?,
        @Parameter(description = "fingerprint(SHA-256 hex). 같은 에러 묶어보기") @RequestParam(required = false) fingerprint: String?,
        @Parameter(description = "visibility 필터 (PUBLIC/WORKSPACE/PRIVATE)", example = "PUBLIC") @RequestParam(required = false) visibility: String?,
        @Parameter(description = "검색어 — title 또는 tag 부분 매치 (case-insensitive).") @RequestParam(required = false) q: String?,
        @Parameter(description = "정렬 · `created` (default, createdAt DESC) / `updated` (updatedAt DESC — 최근 활동 순).")
        @RequestParam(required = false, defaultValue = "created") sort: String,
        @Parameter(description = "다음 페이지 커서(이전 응답의 nextCursor)") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100). `size` 는 deprecated 별칭.") @RequestParam(required = false) limit: Int?,
        @Parameter(description = "[deprecated] `limit` 사용. 하위호환 유지.", deprecated = true) @RequestParam(required = false, defaultValue = "20") size: Int,
    ): ErrorCaseListResponse {
        val parsedStatus = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { ErrorCaseStatus.valueOf(it.uppercase()) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: $status") }
        }
        val parsedVisibility = visibility?.takeIf { it.isNotBlank() }?.let {
            runCatching { Visibility.valueOf(it.uppercase()) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid visibility: $visibility") }
        }
        val result = try {
            listErrorCasesUseCase.invoke(
                ListErrorCasesUseCase.Input(
                    requesterUserId = userId,
                    workspaceId = workspaceId,
                    status = parsedStatus,
                    fingerprint = fingerprint,
                    visibility = parsedVisibility,
                    cursor = cursor,
                    size = limit ?: size,   // limit 우선, 없으면 size(deprecated) 하위호환
                    q = q?.trim()?.takeIf { it.isNotBlank() },
                    sortBy = org.studieojavry.coreapi.errorcase.case.application.port.SortBy.fromCode(sort),
                )
            )
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return ErrorCaseListResponse(
            items = result.items.map { ErrorCaseSummaryResponse.from(it) },
            nextCursor = result.nextCursor,
            hasNext = result.hasNext
        )
    }

    @Operation(
        summary = "내 (혹은 워크스페이스) 케이스의 status 별 개수",
        description = """
            Library > My cases 페이지의 status filter chip 카운트용.
            list 와 동일한 필터 (workspaceId / q) 적용 · status 는 groupBy 대상이라 필터 X.
            응답은 3 status (OPEN / IN_PROGRESS / RESOLVED) 모두 포함 (없으면 0) + total.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "워크스페이스 비멤버", content = [Content()])
    )
    @GetMapping("/status-counts")
    fun statusCounts(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "워크스페이스 ID — 미지정 시 본인 케이스") @RequestParam(required = false) workspaceId: Long?,
        @Parameter(description = "검색어 — title 또는 tag 부분 매치.") @RequestParam(required = false) q: String?,
    ): org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.StatusCountsResponse {
        val result = try {
            countMyCasesByStatusUseCase.invoke(
                CountMyCasesByStatusUseCase.Input(
                    requesterUserId = userId,
                    workspaceId = workspaceId,
                    q = q?.trim()?.takeIf { it.isNotBlank() },
                )
            )
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.StatusCountsResponse(
            byStatus = result.byStatus.mapKeys { it.key.name },
            total = result.total,
        )
    }

    @Operation(
        summary = "공개 에러 케이스 검색 (cursor 무한스크롤)",
        description = """
            `visibility=PUBLIC` 인 모든 케이스를 cursor 기반으로 조회. 인증된 사용자라면 누구나.

            **페이징** — `ListErrorCases` 와 동일: `cursor` 생략 = 첫 페이지, 응답의 `nextCursor` 를 다음 `cursor` 로.
            `size` 기본 20, 상한 100. 정렬 `createdAt DESC, id DESC`.

            **필터** (선택, 모두 AND, 향후 확장)
            - `status` — `OPEN`/`IN_PROGRESS`/`RESOLVED` 등. 잘못된 값 → 400.
            - `fingerprint` — 같은 지문 묶어보기.

            **응답** — 본인 목록과 동일 schema(`ErrorCaseSummaryResponse`) — `tags`, `descriptionPreview` 포함.
            `descriptionPreview` 의 마커(`@snippet(..)`/`@attach(..)`)는 `[code]`/`[file]` 로 치환된 max 120자.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "잘못된 status 값", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/search")
    fun searchPublic(
        @Parameter(hidden = true) @AuthenticationPrincipal viewerUserId: Long?,
        @Parameter(description = "케이스 상태 (OPEN/IN_PROGRESS/RESOLVED 등)", example = "OPEN") @RequestParam(required = false) status: String?,
        @Parameter(description = "fingerprint(SHA-256 hex)") @RequestParam(required = false) fingerprint: String?,
        @Parameter(description = "다음 페이지 커서") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100)", example = "20") @RequestParam(required = false, defaultValue = "20") size: Int,
    ): ErrorCaseListResponse {
        val parsedStatus = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { ErrorCaseStatus.valueOf(it.uppercase()) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: $status") }
        }
        val result = searchPublicErrorCasesUseCase.invoke(
            SearchPublicErrorCasesUseCase.Input(
                status = parsedStatus,
                fingerprint = fingerprint,
                cursor = cursor,
                size = size,
                viewerUserId = viewerUserId,
            )
        )
        return ErrorCaseListResponse(
            items = result.items.map { ErrorCaseSummaryResponse.from(it, result.authors[it.ownerUserId]) },
            nextCursor = result.nextCursor,
            hasNext = result.hasNext,
        )
    }

    @Operation(
        summary = "에러 케이스 상세 조회",
        description = """
            본문/메타/스냅샷 + **연결된 스니펫·첨부 전체**를 반환.

            **권한**: 개인 케이스는 소유자만, 워크스페이스 케이스는 멤버(READ+).

            프런트는 `description` 의 `@snippet(markerId)`/`@attach(markerId)` 토큰을 응답의 `snippets`/`attachments` 와 매칭해 인라인 렌더. 본문에 태그 안 된 항목은 "관련 코드/첨부" 섹션에 노출.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "접근 권한 없음(개인 케이스 비소유자 / 워크스페이스 비멤버)", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()])
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID", example = "15") @PathVariable id: Long
    ): ErrorCaseDetailResponse {
        val errorCase = try {
            getErrorCaseUseCase.invoke(id, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        // owner 가 자기 case 를 본 시각 갱신 — 홈 대시보드 unreadCount 계산용
        if (errorCase.ownerUserId == userId) {
            runCatching { registerCaseViewUseCase.invoke(userId = userId, errorCaseId = id) }
        }
        val mt = getCaseMeTooUseCase.invoke(id, userId)
        val watchedByMe = runCatching { caseWatchlistRepository.exists(id, userId) }.getOrDefault(false)
        return ErrorCaseDetailResponse.from(
            errorCase,
            ErrorCaseDetailResponse.MeTooDto(mt.count, mt.taggedByMe, mt.userIds),
            attachmentUrlResolver(errorCase.visibility),
            watchedByMe = watchedByMe,
        )
    }

    @Operation(
        summary = "“나도 겪었어요” 표시 (멱등)",
        description = """
            본인 케이스에는 누를 수 없음(403). 같은 사용자가 여러 번 호출해도 결과 동일(멱등).
            응답에 현재 count, taggedByMe, userIds 동봉.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "표시됨(또는 이미 있음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "본인 케이스", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping("/{id}/me-too")
    fun markMeToo(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
    ): MeTooResponse {
        val r = try {
            markCaseMeTooUseCase.invoke(id, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: org.studieojavry.coreapi.errorcase.case.application.usecase.CaseMeTooSelfNotAllowedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return MeTooResponse(r.count, r.taggedByMe, r.userIds)
    }

    @Operation(
        summary = "“나도 겪었어요” 해제 (멱등)",
        description = "표시 안 한 사용자가 호출해도 200 — taggedByMe=false 반환."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "해제됨(또는 원래 없음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @DeleteMapping("/{id}/me-too")
    fun unmarkMeToo(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
    ): MeTooResponse {
        val r = try {
            unmarkCaseMeTooUseCase.invoke(id, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        }
        return MeTooResponse(r.count, r.taggedByMe, r.userIds)
    }

    @Operation(
        summary = "케이스 watchlist 추가 (즐겨찾기, 멱등)",
        description = """
            *향후* 케이스 활동을 받아보기 위한 명시적 follow. me-too 와 *완전 별개* — 알림/feed 용.
            **권한**: 케이스 읽기 가능 (PUBLIC 또는 워크스페이스 멤버 또는 owner).
            **자동 등록**: 댓글/step/solution 작성 시에도 자동 추가됨 (use case 단).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "추가됨(또는 이미 있음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "읽기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping("/{id}/watchlist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun addToWatchlist(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
    ) {
        try {
            addCaseToWatchlistUseCase.invoke(id, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }

    @Operation(
        summary = "케이스 watchlist 해제 (멱등)",
        description = "표시 안 한 사용자가 호출해도 204."
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제됨(또는 원래 없음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @DeleteMapping("/{id}/watchlist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeFromWatchlist(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
    ) {
        removeCaseFromWatchlistUseCase.invoke(id, userId)
    }

    @Operation(
        summary = "에러 케이스 부분 수정 (PATCH)",
        description = """
            보낸 필드만 변경 — null/미포함은 기존 값 유지.

            **스니펫/첨부 = 선언형 재연결**
            - `snippetMarkerIds`/`attachmentMarkerIds` 에 **최종 marker 집합**을 보낸다.
            - 서버가 현재 연결과 diff: `desired − current = 추가`, `current − desired = 제거`.
            - `null`(필드 미포함) = 유지, `[]` = 전부 해제, `[m1, m2]` = 그 집합으로 맞춤.
            - 추가 항목은 **존재·소유·미연결** 검증(위반 400).
            - 제거 항목은 unlink → orphan → GC(기본 24h). 화면에선 즉시 사라짐.

            **권한**: 소유자만. (워크스페이스 이동은 불가능 — 범위 밖)

            ⚠️ 스니펫의 **code 자체**는 여기서 못 고친다 — `PATCH /error-snippets/{markerId}` 사용(markerId 유지).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 후 전체 애그리거트 반환"),
        ApiResponse(
            responseCode = "400", description = "잘못된 입력 (미존재·타인 소유·이미 연결된 marker)",
            content = [Content(examples = [ExampleObject(value = """
                {"type":"about:blank","title":"Bad Request","status":400,"detail":"unknown snippet marker(s): [nope_xyz]","instance":"/api/v1/error-cases/15"}
            """)])]
        ),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "소유자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()])
    )
    @PatchMapping("/{id}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID", example = "15") @PathVariable id: Long,
        @Valid @RequestBody request: UpdateErrorCaseRequest
    ): ErrorCaseDetailResponse {
        val updated = try {
            updateErrorCaseUseCase.invoke(
                UpdateErrorCaseCommand(
                    errorCaseId = id,
                    requesterUserId = userId,
                    title = request.title,
                    project = request.project,
                    paste = request.paste,
                    description = request.description,
                    tags = request.tags,
                    snippetMarkerIds = request.snippetMarkerIds,
                    attachmentMarkerIds = request.attachmentMarkerIds,
                    status = request.status,
                    visibility = request.visibility,
                )
            )
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: ErrorCaseLinkException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalArgumentException) {
            // 도메인 transitionTo 의 require 위반(허용되지 않은 상태 전이 등)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return ErrorCaseDetailResponse.from(updated, attachmentUrls = attachmentUrlResolver(updated.visibility))
    }

    @Operation(
        summary = "에러 케이스 삭제 (cascade)",
        description = """
            케이스 + **연결된 스니펫·첨부 전부**를 즉시 삭제(첨부 파일도 같이 cascade).

            **권한**: 소유자만.

            제거를 "케이스에서 일부 스니펫만 떼고 싶다"는 의도로 쓰지 말 것 — 그건 `PATCH /error-cases/{id}` 의 `snippetMarkerIds` 재연결로.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "소유자 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()])
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID", example = "15") @PathVariable id: Long
    ) {
        try {
            deleteErrorCaseUseCase.invoke(id, userId)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseDeleteForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }

    @Operation(
        summary = "케이스 태그 추가 (멱등)",
        description = """
            자유 태그 1건 추가. **trim·소문자** 자동 정규화. 케이스당 최대 20개.
            UI 의 *"엔터 입력 → 칩 생성"* 동작에 1:1 매핑.
            **권한**: 케이스 WRITE (owner 또는 워크스페이스 WRITE+).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "추가됨(또는 이미 있음). 응답에 케이스의 전체 태그 목록."),
        ApiResponse(responseCode = "400", description = "빈 태그 / max 20개 초과", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @PostMapping("/{id}/tags")
    fun addTag(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
        @Valid @RequestBody request: org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request.AddTagRequest,
    ): org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.TagsResponse {
        val r = try {
            addErrorCaseTagUseCase.invoke(id, userId, request.tag)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: ErrorCaseLinkException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.TagsResponse(
            tag = r.tag, added = r.added, removed = false, allTags = r.allTags
        )
    }

    @Operation(
        summary = "케이스 태그 제거 (멱등)",
        description = "UI 의 *칩 X 버튼* 1회 클릭에 1:1 매핑. 권한: 케이스 WRITE."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "제거됨(또는 원래 없음)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "쓰기 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @DeleteMapping("/{id}/tags/{tag}")
    fun removeTag(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "케이스 ID") @PathVariable id: Long,
        @Parameter(description = "태그(URL-encoded)") @PathVariable tag: String,
    ): org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.TagsResponse {
        val r = try {
            removeErrorCaseTagUseCase.invoke(id, userId, tag)
        } catch (e: ErrorCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: ErrorCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response.TagsResponse(
            tag = r.tag, added = false, removed = r.removed, allTags = r.allTags
        )
    }

    companion object {
        /** Idempotency-Key 최대 길이 — DB 컬럼(varchar 200)과 일치. */
        private const val IDEMPOTENCY_KEY_MAX = 200
    }
}
