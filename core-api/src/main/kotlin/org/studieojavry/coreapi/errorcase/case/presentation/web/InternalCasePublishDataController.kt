package org.studieojavry.coreapi.errorcase.case.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import java.time.LocalDateTime

/**
 * **Internal-only** — publish-api 가 publish snapshot 만들 때 사용자의 케이스 전체 데이터를 한 번에 가져옴.
 * gateway 미라우팅. internal JWT (iss=publish-api, aud=core-api) 검증 필요.
 *
 * 권한: caller userId == case owner. 다른 사용자 케이스를 publish 할 수 없게.
 */
@Tag(name = "error-cases-internal", description = "service-to-service: publish snapshot 용 케이스 풀 데이터.")
@RestController
@RequestMapping("/internal/error-cases")
class InternalCasePublishDataController(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
) {

    @Operation(
        summary = "케이스 풀 데이터 (publish snapshot 용)",
        description = "케이스 본문/스냅샷/태그/스니펫/첨부 + 모든 step + 모든 solution 을 한 번에 반환. 권한: caller == case owner."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "케이스 owner 가 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "케이스 없음", content = [Content()]),
    )
    @GetMapping("/{caseId}/full-data")
    @Transactional(readOnly = true)
    fun getFullData(
        @Parameter(hidden = true) @AuthenticationPrincipal callerUserId: Long?,
        @PathVariable caseId: Long,
    ): CaseFullDataResponse {
        val uid = callerUserId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        // errorCaseRepository.findById 가 snippets/attachments/tags 까지 *함께* 채워 반환하므로
        // 별도 snippetRepository/attachmentRepository 호출은 *중복 query* 가 된다 (8→6 query).
        val errorCase = errorCaseRepository.findById(caseId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "case not found: $caseId")
        if (errorCase.ownerUserId != uid) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "not the owner of case $caseId")
        }

        val steps = stepRepository.findAllByErrorCaseId(caseId).sortedBy { it.orderIndex }
        val solutions = solutionRepository.findAllByErrorCaseId(caseId)
        val snippets = errorCase.snippets
        val attachments = errorCase.attachments

        return CaseFullDataResponse(
            id = errorCase.id!!,
            ownerUserId = errorCase.ownerUserId,
            title = errorCase.title,
            description = errorCase.description,
            tags = errorCase.tags.toList(),
            status = errorCase.status.name,
            visibility = errorCase.visibility.name,
            createdAt = errorCase.createdAt,
            occurredAt = errorCase.occurredAt,
            snapshot = errorCase.snapshot?.let {
                SnapshotDto(
                    exceptionClass = it.exceptionClass,
                    exceptionMessage = it.exceptionMessage,
                    rawStackTrace = it.rawStackTrace?.value,
                    fingerprint = it.fingeprint?.value,
                )
            },
            steps = steps.map {
                StepDto(
                    id = it.id!!,
                    orderIndex = it.orderIndex,
                    title = it.title,
                    body = it.body,
                    insight = it.insight,
                    status = it.status.name,
                    attemptType = it.attemptType,
                    createdAt = it.createdAt,
                )
            },
            solutions = solutions.map {
                SolutionDto(
                    id = it.id!!,
                    title = it.title,
                    stepIds = it.stepIds.toList(),
                    createdAt = it.createdAt,
                )
            },
            snippets = snippets.map {
                SnippetDto(
                    markerId = it.markerId,
                    title = it.title,
                    language = it.language,
                    code = it.code,
                    filePathOrClass = it.filePathOrClass,
                    caption = it.caption,
                )
            },
            attachments = attachments.map {
                AttachmentDto(
                    markerId = it.markerId,
                    fileName = it.fileName,
                    kind = it.kind.name,
                    contentType = it.contentType,
                    // storageUrl 필드는 이제 object key 를 실어보낸다(절대 URL 폐기). publish-api 의
                    // 첨부 렌더는 이 key 로 자체 presign/fetch 한다(안정 파일 라우트).
                    storageUrl = it.objectKey,
                )
            },
        )
    }

    @Operation(
        summary = "내 개인 케이스 id 목록 (데모 발행물 시드용)",
        description = """
            caller 가 소유한 **개인 케이스(workspaceId=null)** 의 id 를 최신순 최대 50개 반환.
            publish-api 가 데모 게스트의 "내 발행물" 라이브러리를 지연 시드할 때, 발행 대상
            개인 케이스를 찾기 위해 호출한다. caller 본인 소유만 조회하므로 추가 권한 검사 없음.
            개인 케이스가 없으면 빈 배열.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공 — 내 개인 케이스 id 배열(없으면 빈 배열)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @GetMapping("/mine/personal-ids")
    @Transactional(readOnly = true)
    fun myPersonalCaseIds(
        @Parameter(hidden = true) @AuthenticationPrincipal callerUserId: Long?,
    ): List<Long> {
        val uid = callerUserId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        return errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = null,
                ownerUserId = uid,
                status = null,
                fingerprint = null,
                visibility = null,
                cursorCreatedAt = null,
                cursorId = null,
                limit = PERSONAL_IDS_LIMIT,
            ),
        ).map { it.id }
    }

    @Operation(
        summary = "케이스 배치 status (원본 살아있는지 / 마지막 수정 시각)",
        description = """
            publish-api 가 발행물의 원본 case 라이프사이클(삭제/수정)을 lazy 판정할 때 사용.
            요청한 caseId 중 *존재하는* 것만 `{id, updatedAt}` 로 반환 —
            응답에 없는 id 는 삭제된 것으로 간주(publish-api 가 SOURCE_DELETED 처리).
            권한 검사 없음(updatedAt 메타데이터만, internal-only).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
    )
    @PostMapping("/status")
    @Transactional(readOnly = true)
    fun getStatuses(
        @Parameter(hidden = true) @AuthenticationPrincipal callerUserId: Long?,
        @RequestBody request: CaseStatusRequest,
    ): CaseStatusResponse {
        callerUserId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing principal")
        if (request.caseIds.isEmpty()) return CaseStatusResponse(emptyList())
        val statuses = errorCaseRepository.findSummariesByIds(request.caseIds.toSet())
            .map { CaseStatusItem(id = it.id, updatedAt = it.updatedAt) }
        return CaseStatusResponse(statuses)
    }

    private companion object {
        // 개인 케이스 데모 시드는 소수(현재 3건)지만 여유 있게 상한.
        const val PERSONAL_IDS_LIMIT = 50
    }

    // ──────────────────────── DTO ────────────────────────
    data class CaseStatusRequest(val caseIds: List<Long>)
    data class CaseStatusResponse(val statuses: List<CaseStatusItem>)
    data class CaseStatusItem(val id: Long, val updatedAt: LocalDateTime)

    data class CaseFullDataResponse(
        val id: Long,
        val ownerUserId: Long,
        val title: String,
        val description: String?,
        val tags: List<String>,
        val status: String,
        val visibility: String,
        val createdAt: LocalDateTime,
        val occurredAt: LocalDateTime?,
        val snapshot: SnapshotDto?,
        val steps: List<StepDto>,
        val solutions: List<SolutionDto>,
        val snippets: List<SnippetDto>,
        val attachments: List<AttachmentDto>,
    )

    data class SnapshotDto(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val rawStackTrace: String?,
        val fingerprint: String?,
    )

    data class StepDto(
        val id: Long,
        val orderIndex: Int,
        val title: String,
        val body: String?,
        val insight: String?,
        val status: String,
        val attemptType: String?,
        val createdAt: LocalDateTime,
    )

    data class SolutionDto(
        val id: Long,
        val title: String,
        val stepIds: List<Long>,
        val createdAt: LocalDateTime,
    )

    data class SnippetDto(
        val markerId: String,
        val title: String,
        val language: String,
        val code: String,
        val filePathOrClass: String?,
        val caption: String?,
    )

    data class AttachmentDto(
        val markerId: String,
        val fileName: String,
        val kind: String,
        val contentType: String,
        val storageUrl: String,
    )
}
