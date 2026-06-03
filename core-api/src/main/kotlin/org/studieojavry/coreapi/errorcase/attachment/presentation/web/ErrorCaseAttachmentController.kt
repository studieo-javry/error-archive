package org.studieojavry.coreapi.errorcase.attachment.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
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
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentDownloadForbiddenException
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentNotFoundException
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.CreateAttachmentUseCase
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.DeleteAttachmentUseCase
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.DownloadAttachmentUseCase
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.storage.AttachmentFileNotFoundException
import org.studieojavry.coreapi.errorcase.attachment.presentation.web.dto.response.AttachmentUploadResponse
import java.nio.charset.StandardCharsets

@Tag(
    name = "error-attachments",
    description = "첨부 파일(이미지/로그 등) 독립 리소스. 케이스 생성 전에 업로드 → markerId 발급 → 본문 `@attach(markerId)` 임베드 또는 케이스 `attachmentMarkerIds` 로 연결."
)
@RestController
@RequestMapping("/api/v1/error-attachments")
class ErrorCaseAttachmentController(
    private val createAttachmentUseCase: CreateAttachmentUseCase,
    private val downloadAttachmentUseCase: DownloadAttachmentUseCase,
    private val deleteAttachmentUseCase: DeleteAttachmentUseCase
) {

    @Operation(
        summary = "첨부 업로드 (multipart)",
        description = """
            파일을 업로드하고 markerId 를 발급한다. 이 시점엔 케이스에 안 묶인 상태(orphan).

            **multipart/form-data**
            - `file` (필수) — 업로드할 파일.
            - `title` (선택) — 표시용 제목.
            - `caption` (선택) — 부가 설명.

            **언제 사용**
            - 케이스 등록 페이지에서 스크린샷·로그 파일을 첨부할 때. 응답의 `markerId` 를 본문에 `@attach(markerId)` 로 태그하거나 케이스 생성 요청의 `attachmentMarkerIds` 에 포함.

            **주의**
            - 케이스 미연결 상태로 TTL(기본 24h) 지나면 orphan GC 가 삭제(파일 + row).
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201", description = "업로드 성공",
            content = [Content(examples = [ExampleObject(value = """
                {"markerId":"f0c12ab9","embedToken":"@attach(f0c12ab9)","fileName":"screenshot.png","contentType":"image/png","size":48213,"kind":"IMAGE","storageUrl":"local:f0c12ab9"}
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
        @Parameter(description = "표시용 제목(선택)") @RequestPart("title", required = false) title: String?,
        @Parameter(description = "부가 설명(선택)") @RequestPart("caption", required = false) caption: String?
    ): AttachmentUploadResponse {

        require(!file.isEmpty) { "file can not be empty" }

        // TODO: 바인딩 로직은 뒷단으로 빼기
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
            storageUrl = result.storageUrl
        )
    }

    @Operation(
        summary = "첨부 다운로드 / 미리보기",
        description = """
            첨부 파일을 응답 본문(`Content-Type` 자동 추론) + `Content-Disposition` 헤더로 내려준다.

            **권한**
            - 미연결(케이스에 안 묶인) 첨부: 업로더 본인만.
            - 연결된 첨부: 케이스 소유자만(현행). 추후 워크스페이스 멤버까지 확장 예정.

            **표시 모드**
            - `?download=true` → `Content-Disposition: attachment`(다운로드 강제).
            - 기본 → `inline`(브라우저에서 미리보기, 이미지 등).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "파일 바이트"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "다운로드 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "첨부 메타 또는 파일 없음", content = [Content()])
    )
    @GetMapping("/{markerId}")
    fun download(
        @Parameter(hidden = true) @AuthenticationPrincipal userId: Long,
        @Parameter(description = "첨부 markerId", example = "f0c12ab9") @PathVariable markerId: String,
        @Parameter(description = "true=다운로드 강제, false=inline 미리보기") @RequestParam(name = "download", required = false, defaultValue = "false") forceDownload: Boolean,
        @Parameter(hidden = true) request: HttpServletRequest
    ): ResponseEntity<ByteArrayResource> {

        println("URI=${request.requestURI}")
        println("Authorization=${request.getHeader("Authorization")}")

        // TODO: 다운로드 권한은 로그인한 사용자 누구에나 (노출된 에러 케이스의 경우)

        val result = try {
            downloadAttachmentUseCase.invoke(markerId, userId)
        } catch (e: AttachmentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: AttachmentFileNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
        } catch (e: AttachmentDownloadForbiddenException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
        }

        val mediaType = runCatching { MediaType.parseMediaType(result.contentType) }
            .getOrElse { MediaTypeFactory.getMediaType(result.fileName).orElse(MediaType.APPLICATION_OCTET_STREAM) }

        val disposition = ContentDisposition
            .builder(if (forceDownload) "attachment" else "inline")
            .filename(result.fileName, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .contentType(mediaType)
            .contentLength(result.size)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(ByteArrayResource(result.bytes))
    }

    @Operation(
        summary = "첨부 즉시 삭제",
        description = """
            업로더 본인만, **미연결(errorCaseId == null)** 인 첨부만 삭제 가능. 파일 + row 함께 즉시 삭제.
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
