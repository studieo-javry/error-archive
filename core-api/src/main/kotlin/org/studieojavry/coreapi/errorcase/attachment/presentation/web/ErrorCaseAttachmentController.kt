package org.studieojavry.coreapi.errorcase.attachment.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.attachment.application.command.CreateAttachmentCommand
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentDeleteForbiddenException
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentNotFoundException
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.CreateAttachmentUseCase
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.DeleteAttachmentUseCase
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.ListMyPendingAttachmentsUseCase
import org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response.AttachmentUploadResponse
import org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response.MyPendingAttachmentResponse

@Tag(
    name = "error-attachments",
    description = "첨부 파일(이미지/로그 등) 독립 리소스. 케이스 생성 전에 업로드 → markerId 발급 → 본문 `@attach(markerId)` 임베드 또는 케이스 `attachmentMarkerIds` 로 연결. 조회/렌더는 케이스 상세 응답이 내려주는 **presigned URL**(S3/MinIO 직접) 을 사용한다."
)
@RestController
@RequestMapping("/api/v1/error-attachments")
class ErrorCaseAttachmentController(
    private val createAttachmentUseCase: CreateAttachmentUseCase,
    private val deleteAttachmentUseCase: DeleteAttachmentUseCase,
    private val listMyPendingAttachmentsUseCase: ListMyPendingAttachmentsUseCase,
) {

    @Operation(
        summary = "첨부 업로드 (multipart)",
        description = """
            파일을 스토리지에 업로드하고 markerId 를 발급한다. 이 시점엔 케이스에 안 묶인 상태(orphan).

            **multipart/form-data**
            - `file` (필수) — 업로드할 파일.
            - `title` (선택) — 표시용 제목.
            - `caption` (선택) — 부가 설명.

            **응답**
            - `markerId` 를 본문에 `@attach(markerId)` 로 태그하거나 케이스 생성 요청의 `attachmentMarkerIds` 에 포함.
            - `previewUrl` — 업로드 직후 위저드 미리보기용 presigned inline URL(짧은 TTL, 업로더 전용).

            **주의**
            - 케이스 미연결 상태로 TTL(기본 24h) 지나면 orphan GC 가 삭제(스토리지 오브젝트 + row).
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201", description = "업로드 성공",
            content = [Content(examples = [ExampleObject(value = """
                {"markerId":"f0c12ab9","embedToken":"@attach(f0c12ab9)","fileName":"screenshot.png","contentType":"image/png","size":48213,"kind":"IMAGE","previewUrl":"http://localhost:9000/error-archive-attachments/attachments/f0/f0c12ab9?X-Amz-..."}
            """)])]
        ),
        ApiResponse(responseCode = "400", description = "파일 비어있음 / 파일명 없음", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()])
    )
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "업로드할 파일") @RequestPart("file") file: MultipartFile,
        @Parameter(description = "표시용 제목(선택)") @RequestParam("title", required = false) title: String?,
        @Parameter(description = "부가 설명(선택)") @RequestParam("caption", required = false) caption: String?
    ): AttachmentUploadResponse {

        require(!file.isEmpty) { "file can not be empty" }

        val originFileName = file.originalFilename?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("file name can not be empty")
        val contentType = file.contentType?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        val result = createAttachmentUseCase.invoke(
            CreateAttachmentCommand(
                uploaderUserId = userId,
                title = title,
                caption = caption,
                fileName = originFileName,
                contentType = contentType,
                size = file.size,
                bytes = file.bytes
            )
        )

        return AttachmentUploadResponse(
            markerId = result.markerId,
            embedToken = result.embedToken,
            fileName = result.fileName,
            contentType = result.contentType,
            size = result.size,
            kind = result.kind,
            previewUrl = result.previewUrl
        )
    }

    @Operation(
        summary = "내 미연결(pending) 첨부 목록",
        description = """
            업로더 본인이 올렸지만 아직 케이스에 연결되지 않은 첨부 목록 + fresh presigned inline URL.

            **용도**: 작성 화면의 "이번에 올린 첨부" 트레이 재조회. 업로드 응답의 previewUrl 은 1회성(짧은 TTL)이라
            새로고침/복귀 시 다시 볼 수 없으므로 이 엔드포인트로 재조회한다.

            **파라미터**
            - `linked` (선택, 기본 false) — 현재는 `false`(pending) 만 지원. 연결된 첨부는 케이스 상세 응답이 내려주므로
              `linked=true` 는 400.

            최신순, 최대 100건. 본인 첨부만 반환.
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
    ): List<MyPendingAttachmentResponse> {
        if (linked) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "linked=true is not supported; use case detail response for linked attachments")
        }
        return listMyPendingAttachmentsUseCase.invoke(userId).map {
            MyPendingAttachmentResponse(
                markerId = it.markerId,
                embedToken = it.embedToken,
                fileName = it.fileName,
                contentType = it.contentType,
                size = it.size,
                kind = it.kind,
                title = it.title,
                caption = it.caption,
                previewUrl = it.previewUrl,
                uploadedAt = it.uploadedAt,
            )
        }
    }

    @Operation(
        summary = "첨부 즉시 삭제",
        description = """
            업로더 본인만, **미연결(errorCaseId == null)** 인 첨부만 삭제 가능. 스토리지 오브젝트 + row 함께 즉시 삭제.
            케이스에 연결된 첨부를 떼고 싶다면 `PATCH /error-cases/{id}` 의 `attachmentMarkerIds` 재연결을 사용하라.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "업로더 아님 / 이미 케이스에 연결됨", content = [Content()]),
        ApiResponse(responseCode = "404", description = "첨부 없음", content = [Content()])
    )
    @DeleteMapping("/{markerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "첨부 markerId") @PathVariable markerId: String
    ) {
        try {
            deleteAttachmentUseCase.invoke(markerId, userId)
        } catch (e: AttachmentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: AttachmentDeleteForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }
    }
}
