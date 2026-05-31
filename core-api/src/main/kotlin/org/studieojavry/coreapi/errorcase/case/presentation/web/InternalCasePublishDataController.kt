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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
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
                    storageUrl = it.storageUrl,
                    previewText = it.previewText,
                )
            },
        )
    }

    // ──────────────────────── DTO ────────────────────────
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
        val storageUrl: String,
        val previewText: String?,
    )
}
