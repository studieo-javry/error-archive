package org.studieojavry.publishapi.publishment.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
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
import org.studieojavry.publishapi.publishment.application.port.CoreCaseAccessDeniedException
import org.studieojavry.publishapi.publishment.application.port.CoreCaseNotFoundException
import org.studieojavry.publishapi.publishment.application.port.MyPublishmentSort
import org.studieojavry.publishapi.publishment.application.usecase.CreatePublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.DeletePublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.GetMyPublishmentSummaryUseCase
import org.studieojavry.publishapi.publishment.application.usecase.GetMyPublishmentByCaseUseCase
import org.studieojavry.publishapi.publishment.application.usecase.ListMyPublishmentsUseCase
import org.studieojavry.publishapi.publishment.application.usecase.HtmlRenderer
import org.studieojavry.publishapi.publishment.application.usecase.IdempotencyConflictException
import org.studieojavry.publishapi.publishment.application.usecase.MarkdownRenderer
import org.studieojavry.publishapi.publishment.application.usecase.PreviewPublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentAlreadyExistsException
import org.studieojavry.publishapi.publishment.application.usecase.RecordPublishmentDownloadUseCase
import org.studieojavry.publishapi.publishment.application.usecase.PublishInput
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentContentException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentForbiddenException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentNotFoundException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentGoneException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentSourceDeletedException
import org.studieojavry.publishapi.publishment.application.usecase.RepublishPublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.RestorePublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.UnpublishPublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.UpdatePublishmentMetadataUseCase
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import org.studieojavry.publishapi.publishment.presentation.dto.PublishRequest
import org.studieojavry.publishapi.publishment.presentation.dto.PublishmentListItemResponse
import org.studieojavry.publishapi.publishment.presentation.dto.PublishmentListResponse
import org.studieojavry.publishapi.publishment.presentation.dto.PublishmentResponse
import org.studieojavry.publishapi.publishment.presentation.dto.PublishmentSummaryResponse
import org.studieojavry.publishapi.publishment.presentation.dto.UpdateMetadataRequest
import org.studieojavry.publishapi.shared.config.PagePublicProperties

@Tag(name = "publishments", description = "에러 케이스를 publish 한 자산 — 링크 / MD / PDF 로 공유.")
@RestController
@RequestMapping("/api/v1/publishments")
class PublishmentController(
    private val createPublishmentUseCase: CreatePublishmentUseCase,
    private val updateMetadataUseCase: UpdatePublishmentMetadataUseCase,
    private val republishUseCase: RepublishPublishmentUseCase,
    private val unpublishUseCase: UnpublishPublishmentUseCase,
    private val restoreUseCase: RestorePublishmentUseCase,
    private val deletePublishmentUseCase: DeletePublishmentUseCase,
    private val getByCaseUseCase: GetMyPublishmentByCaseUseCase,
    private val listMyUseCase: ListMyPublishmentsUseCase,
    private val summaryUseCase: GetMyPublishmentSummaryUseCase,
    private val previewPublishmentUseCase: PreviewPublishmentUseCase,
    private val recordDownloadUseCase: RecordPublishmentDownloadUseCase,
    private val pageProps: PagePublicProperties,
    private val attachmentPresigner: org.studieojavry.publishapi.publishment.infrastructure.attachment.AttachmentPresigner,
) {
    /** 첨부 objectKey → presigned inline URL (미리보기/MD 렌더 시점 발급). */
    /** preview(transient, slug 無)용 presigned inline URL — 발행물은 안정 라우트 사용. */
    private val imageEmbedUrl: (String) -> String? = { attachmentPresigner.viewUrl(it) }

    /**
     * NOTE: publish 정책 관련
     * 1. 마스킹 옵션 도입 여부. 현재 문자열 매칭 후 "치환" 방식으로 도입되어 있는 상태
     * 2. 옵션 상세 정책: 공개정책 (public, unlisted, private), timestyle, language, theme, attachments
     *    - timestyle: 상대 시간 / 표시 안 함
     *    - language: 한국어 / english
     *    - theme: light / dark
     *    - attachments: 링크 / 인라인 (image) / 제외
     * 3. 각 step 소요 시간 표시 - toggle on/off 방식 - ex) 3hr
     */

    @Operation(
        summary = "케이스 publish (snapshot 생성)",
        description = """
            현재 사용자의 케이스를 불변 스냅샷으로 publish. step/solution 선별, 마스킹, 옵션 모두 받음.
            응답에 `slug` + 공개 URL 포함. visibility default = PUBLIC.

            멱등성: `Idempotency-Key` 헤더 필수. 같은 키+같은 본문 재요청은 최초 발행물을
            그대로 재생(200 + `Idempotent-Replayed: true`), 새로 발행하지 않음. 최초 발행은 201.
            같은 키로 다른 본문이면 409.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "발행됨"),
        ApiResponse(responseCode = "200", description = "멱등 재생 (같은 Idempotency-Key 재요청)"),
        ApiResponse(responseCode = "400", description = "Idempotency-Key 헤더 누락", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 owner 아님 (core-api 에서)", content = [Content()]),
        ApiResponse(responseCode = "404", description = "원본 케이스 없음", content = [Content()]),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용(다른 본문) / 동시 중복 요청 / **이미 발행된 케이스**(케이스당 1 canonical — message 에 기존 slug)", content = [Content()]),
        ApiResponse(responseCode = "422", description = "발행 정책 위반 — 선별된 step 0개", content = [Content()]),
    )
    @PostMapping
    fun publish(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Parameter(description = "멱등 키 (필수) — 클라이언트 생성 UUID 권장")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: PublishRequest,
    ): ResponseEntity<PublishmentResponse> {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required")
        if (key.length > IDEMPOTENCY_KEY_MAX) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long (max $IDEMPOTENCY_KEY_MAX)")
        }
        val requestHash = request.canonicalHash()
        val result = try {
            createPublishmentUseCase.invoke(uid, key, requestHash, request.toInput())
        } catch (e: CoreCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CoreCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: PublishmentContentException) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.message, e)
        } catch (e: IdempotencyConflictException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
        // PublishmentAlreadyExistsException(케이스당 1 canonical)은 아래 @ExceptionHandler 가 처리(slug 노출).
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status)
            .header("Idempotent-Replayed", result.replayed.toString())
            .body(PublishmentResponse.from(result.publishment, pageProps.publicBaseUrl))
    }

    /**
     * Q. 발행 전 미리보기:
     *    1. 말그대로, 발행물을 발행하기 전에 어떤 형태로 보여지는 지를 미리 보여주는 API?
     *    2. 발행물은 만들었지만, 아직 이를 다운로드하거나 확인하지 않은 상태. 미리보기로 만들어진 결과를 열어보는 API?
     */

    @Operation(
        summary = "발행 전 미리보기 (저장 X)",
        description = """
            실제 발행과 동일한 선별/마스킹/옵션을 적용한 HTML 을 저장 없이 반환.
            요청 본문은 `POST /publishments` 와 동일한 `PublishRequest`.
            공개 페이지(`/p/{slug}`) 와 같은 렌더러를 쓰므로 발행 결과와 1:1 (WYSIWYG).
            인증 필요 — case owner 만 (core-api 에서 검증).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "미리보기 HTML (text/html)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 owner 아님 (core-api 에서)", content = [Content()]),
        ApiResponse(responseCode = "404", description = "원본 케이스 없음", content = [Content()]),
        ApiResponse(responseCode = "422", description = "발행 정책 위반 — 선별된 step 0개", content = [Content()]),
    )
    @PostMapping("/preview", produces = [MediaType.TEXT_HTML_VALUE])
    fun preview(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: PublishRequest,
    ): ResponseEntity<String> {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val transient = try {
            previewPublishmentUseCase.invoke(uid, request.toInput())
        } catch (e: CoreCaseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: CoreCaseAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        } catch (e: PublishmentContentException) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.message, e)
        }
        val html = HtmlRenderer.render(transient, forPdf = false, publicBaseUrl = pageProps.publicBaseUrl, imageEmbedUrl = imageEmbedUrl)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
            .body(html)
    }

    @Operation(
        summary = "내 publish 목록 (Library, cursor Load More)",
        description = """
            keyset(cursor) 페이징 + 검색/필터/정렬. 응답은 slim(본문 content 제외).
            - `q`: 발행 title 부분매치(대소문자 무시).
            - `sort`: `published`(default) / `views`.
            - 필터: `visibility`(PUBLIC/UNLISTED).
            - 페이징: `cursor` 생략=첫 페이지, 응답 `nextCursor` 를 다음 `cursor` 로. `hasNext=false` 면 끝.
            - `totalCount` 는 첫 페이지에서만 채워짐(이후 null).
            - 참고: `sourceState`/`sourceStale` 은 응답 필드로만 노출(배지용) — 필터는 지원 안 함.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "잘못된 visibility 값", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/me")
    fun listMine(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @Parameter(description = "검색어 — 발행 title 부분매치") @RequestParam(required = false) q: String?,
        @Parameter(description = "PUBLIC/UNLISTED") @RequestParam(required = false) visibility: String?,
        @Parameter(description = "정렬 · published(default)/views") @RequestParam(required = false, defaultValue = "published") sort: String,
        @Parameter(description = "다음 페이지 커서(이전 응답 nextCursor)") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기(기본 20, 최대 100)") @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): PublishmentListResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val vis = visibility?.takeIf { it.isNotBlank() }?.let {
            runCatching { Visibility.valueOf(it.uppercase()) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid visibility: $visibility") }
        }
        val result = listMyUseCase.invoke(
            ListMyPublishmentsUseCase.Input(
                requesterUserId = uid,
                q = q?.trim()?.takeIf { it.isNotBlank() },
                visibility = vis,
                sort = MyPublishmentSort.fromCode(sort),
                cursor = cursor,
                limit = limit,
            )
        )
        return PublishmentListResponse(
            items = result.items.map { PublishmentListItemResponse.from(it, pageProps.publicBaseUrl) },
            nextCursor = result.nextCursor,
            hasNext = result.hasNext,
            totalCount = result.totalCount,
        )
    }

    @Operation(summary = "발행 내리기 (soft unpublish — 공개 라우트 410, 재공개 가능)")
    @PostMapping("/by-slug/{slug}/unpublish")
    fun unpublish(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable slug: String,
    ): PublishmentResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return PublishmentResponse.from(runOwned { unpublishUseCase.invoke(slug, uid) }, pageProps.publicBaseUrl)
    }

    @Operation(summary = "다시 공개 (UNPUBLISHED → LIVE 복구)")
    @PostMapping("/by-slug/{slug}/publish")
    fun restore(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable slug: String,
    ): PublishmentResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return PublishmentResponse.from(runOwned { restoreUseCase.invoke(slug, uid) }, pageProps.publicBaseUrl)
    }

    @Operation(
        summary = "발행물 영구 삭제 (hard delete — 되돌릴 수 없음)",
        description = """
            발행물을 완전히 제거한다. 내리기(unpublish)와 달리 slug·스냅샷·counters 를 보존하지 않는다:
            공개 페이지는 404 가 되고 slug 는 반납되어 같은 주소로 다시 발행할 수 없다. 멱등 기록도 함께 정리.
            원본 에러 케이스와 재발행 권한은 그대로다. 소유자 전용.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "내 발행물 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "해당 slug 발행물 없음", content = [Content()]),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/by-slug/{slug}")
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable slug: String,
    ) {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        try {
            deletePublishmentUseCase.invoke(slug, uid)
        } catch (e: PublishmentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: PublishmentForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }

    /** owner-guarded lifecycle 액션 공통 예외 매핑 (404/403). */
    private inline fun runOwned(block: () -> CasePublishment): CasePublishment = try {
        block()
    } catch (e: PublishmentNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: PublishmentForbiddenException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    }

    @Operation(
        summary = "케이스의 발행 상태 조회 (by-case)",
        description = """
            케이스 상세에서 "발행하기 vs 발행됨(링크)" 를 판별하기 위한 단건 조회.
            요청자가 소유한 케이스의 canonical 발행물을 반환. 발행 안 됐으면 404.
            (케이스당 1 canonical 정책이라 단건.)
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발행됨 — 발행물 반환"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "404", description = "해당 케이스의 내 발행물 없음", content = [Content()]),
    )
    @GetMapping("/by-case/{caseId}")
    fun getByCase(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long?,
        @PathVariable caseId: Long,
    ): PublishmentResponse {
        val uid = userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        val p = getByCaseUseCase.invoke(caseId, uid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no publishment for case $caseId")
        return PublishmentResponse.from(p, pageProps.publicBaseUrl)
    }

    private fun PublishRequest.toInput() = PublishInput(
        caseId = this.caseId,
        title = this.title,
        summary = this.summary,
        visibility = runCatching { Visibility.valueOf((this.visibility ?: "PUBLIC").uppercase()) }
            .getOrDefault(Visibility.PUBLIC),
        includeStepIds = this.includeStepIds?.toSet(),
        includeSolutionIds = this.includeSolutionIds?.toSet(),
        excludedSnippetMarkerIds = this.excludedSnippetMarkerIds?.toSet() ?: emptySet(),
        excludedAttachmentMarkerIds = this.excludedAttachmentMarkerIds?.toSet() ?: emptySet(),
        options = this.options ?: PublishOptions.defaults(),
    )

    /**
     * 요청 본문의 안정적 해시 — 같은 Idempotency-Key 로 다른 payload 재사용을 탐지.
     * JSON 필드/배열 순서에 무관하도록 정렬·정규화한 canonical 문자열을 SHA-256.
     */
    private fun PublishRequest.canonicalHash(): String {
        val canonical = listOf(
            caseId.toString(),
            title.orEmpty(),
            summary.orEmpty(),
            (visibility ?: "PUBLIC").uppercase(),
            includeStepIds?.sorted()?.joinToString(",") ?: "ALL",
            includeSolutionIds?.sorted()?.joinToString(",") ?: "ALL",
            excludedSnippetMarkerIds?.sorted()?.joinToString(",").orEmpty(),
            excludedAttachmentMarkerIds?.sorted()?.joinToString(",").orEmpty(),
            (options ?: PublishOptions.defaults()).toString(),
        ).joinToString("")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 케이스당 1 canonical — 이미 발행된 케이스 재발행 시도 → 409 + 기존 slug 안내.
     * body 에 `existingSlug`, `Location` 헤더로 기존 리소스 경로 → 클라이언트가 편집/재발행으로 유도.
     */
    @ExceptionHandler(PublishmentAlreadyExistsException::class)
    fun handleAlreadyPublished(e: PublishmentAlreadyExistsException): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "이미 발행된 케이스")
        pd.setProperty("code", "PUBLISHMENT_ALREADY_EXISTS")
        pd.setProperty("existingSlug", e.existingSlug)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.LOCATION, "/api/v1/publishments/by-slug/${e.existingSlug}")
            .body(pd)
    }

    private companion object {
        const val IDEMPOTENCY_KEY_MAX = 200
    }
}
