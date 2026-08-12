package org.studieojavry.coreapi.errorcase.snippet.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.attachment.presentation.web.ErrorCaseAttachmentController
import org.studieojavry.coreapi.errorcase.snippet.application.command.CreateSnippetCommand
import org.studieojavry.coreapi.errorcase.snippet.application.command.UpdateSnippetCommand
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.CreateSnippetUseCase
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.DeleteSnippetUseCase
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.ListMyPendingSnippetsUseCase
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.SnippetAccessDeniedException
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.SnippetDeleteForbiddenException
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.SnippetNotFoundException
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.UpdateSnippetUseCase
import org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.request.CodeSnippetCreateRequest
import org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.request.CodeSnippetUpdateRequest
import org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response.CodeSnippetCreateResponse
import org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response.CodeSnippetDetailResponse
import org.studieojavry.coreapi.errorcase.snippet.presentation.web.dto.response.MyPendingSnippetResponse


/**
 * 코드 스니펫 독립 생성/수정/삭제 (첨부 ErrorCaseAttachmentController 와 대칭).
 */
@Tag(
    name = "error-snippets",
    description = "코드 스니펫(코드 블록) 독립 리소스. 케이스 생성 전에 만들어 markerId 를 받고, 본문 `@snippet(markerId)` 임베드 또는 케이스 `snippetMarkerIds` 로 연결."
)
@RestController
@RequestMapping("/api/v1/error-snippets")
class SnippetController(
    private val createSnippetUseCase: CreateSnippetUseCase,
    private val updateSnippetUseCase: UpdateSnippetUseCase,
    private val deleteSnippetUseCase: DeleteSnippetUseCase,
    private val listMyPendingSnippetsUseCase: ListMyPendingSnippetsUseCase,
) {

    @Operation(
        summary = "내 미연결(pending) 스니펫 목록",
        description = """
            작성자 본인이 만들었지만 아직 케이스에 연결되지 않은 스니펫 목록(code 포함).

            **용도**: 작성 화면 "추가한 스니펫" 트레이 재조회. 스니펫은 DB 텍스트라 presigned URL 없이 code 를 직접 반환.
            **파라미터**: `linked`(선택, 기본 false). 현재 false(pending)만 지원 — 연결된 스니펫은 케이스 상세로. `linked=true` 는 400.

            최신순, 최대 100건, 본인 것만.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "linked=true 미지원", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @GetMapping("/mine")
    fun listMine(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "연결 상태 필터 — 현재 false(pending)만 지원") @RequestParam(required = false, defaultValue = "false") linked: Boolean,
    ): List<MyPendingSnippetResponse> {
        if (linked) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "linked=true is not supported; use case detail response for linked snippets")
        }
        return listMyPendingSnippetsUseCase.invoke(userId).map {
            MyPendingSnippetResponse(
                markerId = it.markerId,
                embedToken = it.embedToken,
                title = it.title,
                language = it.language,
                filePathOrClass = it.filePathOrClass,
                lineRange = it.lineRange,
                caption = it.caption,
                code = it.code,
                uploadedAt = it.uploadedAt,
            )
        }
    }

    @Operation(
        summary = "스니펫 생성 (독립)",
        description = """
            새 스니펫을 만들고 markerId 를 발급한다. 이 시점엔 케이스에 안 묶인 상태(`errorCaseId=null`, orphan).

            **언제 사용**
            - 사용자가 케이스 등록 페이지에서 "코드 스니펫 추가"를 누른 직후. 응답의 `markerId` 를 본문에 `@snippet(markerId)` 로 태그하거나, 케이스 생성 요청의 `snippetMarkerIds` 에 포함해 연결한다.

            **주의**
            - 케이스에 연결되지 않은 채 **TTL(기본 24h)** 이 지나면 orphan GC 가 삭제.
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201", description = "생성됨",
            content = [Content(examples = [ExampleObject(value = """
                {"markerId":"7a7d35e9","embedToken":"@snippet(7a7d35e9)","title":"OrderService.calc","language":"kotlin","caption":null}
            """)])]
        ),
        ApiResponse(responseCode = "400", description = "검증 실패(language/code 비어있음 등)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CodeSnippetCreateRequest
    ): CodeSnippetCreateResponse {
        val result = createSnippetUseCase.invoke(
            CreateSnippetCommand(
                uploaderUserId = userId,
                title = request.title,
                language = request.language,
                filePathOrClass = request.filePathOrClass,
                lineRange = request.lineRange,
                caption = request.caption,
                code = request.code
            )
        )
        return CodeSnippetCreateResponse(
            markerId = result.markerId,
            embedToken = result.embedToken,
            title = result.title,
            language = result.language,
            caption = result.caption
        )
    }

    @Operation(
        summary = "스니펫 내용 수정 (PATCH)",
        description = """
            **markerId 는 유지**한 채 내용(`code`/`title`/`language`/`filePathOrClass`/`lineRange`/`caption`)만 갱신.
            업로더 본인만 가능. 보낸 필드만 변경(null/미포함 = 유지). `uploadedBy`/`uploadedAt`/`errorCaseId`(연결) 은 변경 불가.

            **언제 사용**
            - 등록한 코드 블록의 내용을 그 자리에서 고치고 싶을 때. markerId 가 유지되므로 본문의 `@snippet(markerId)` 인라인 참조는 그대로 작동.
            - 케이스에 이미 연결된 스니펫도 수정 가능(연결과 내용은 별개 책임).
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200", description = "수정 결과(전체 필드 포함)",
            content = [Content(examples = [ExampleObject(value = """
                {"markerId":"7a7d35e9","embedToken":"@snippet(7a7d35e9)","title":"OrderService.calc (fixed)","language":"kotlin","filePathOrClass":"OrderService","lineRange":"10-20","caption":null,"code":"fun calc() = 42","errorCaseId":15}
            """)])]
        ),
        ApiResponse(responseCode = "400", description = "검증 실패(code/language 가 빈 문자열 등)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "업로더 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "스니펫 없음", content = [Content()])
    )
    @PatchMapping("/{markerId}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "스니펫 markerId", example = "7a7d35e9") @PathVariable markerId: String,
        @Valid @RequestBody request: CodeSnippetUpdateRequest
    ): CodeSnippetDetailResponse {
        val updated = try {
            updateSnippetUseCase.invoke(
                UpdateSnippetCommand(
                    markerId = markerId,
                    requesterUserId = userId,
                    title = request.title,
                    language = request.language,
                    filePathOrClass = request.filePathOrClass,
                    lineRange = request.lineRange,
                    caption = request.caption,
                    code = request.code
                )
            )
        } catch (e: SnippetNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: SnippetAccessDeniedException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
        return CodeSnippetDetailResponse.from(updated)
    }

    @Operation(
        summary = "스니펫 즉시 삭제",
        description = """
            업로더 본인만, **미연결(errorCaseId == null)** 인 스니펫만 삭제 가능.
            케이스에 연결된 스니펫을 떼고 싶다면 `PATCH /error-cases/{id}` 의 `snippetMarkerIds` 에서 빼라(본문 참조 깨짐 방지).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "업로더 아님 / 이미 케이스에 연결되어 있음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "스니펫 없음", content = [Content()])
    )
    @DeleteMapping("/{markerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "스니펫 markerId") @PathVariable markerId: String
    ) {
        try {
            deleteSnippetUseCase.invoke(markerId, userId)
        } catch (e: SnippetNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: SnippetDeleteForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }
}
