package org.studieojavry.insightapi.activity.infrastructure.dlq.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.insightapi.activity.infrastructure.dlq.ActivityEventDlqEntity
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqFailureKind
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqStatus
import java.time.Instant

@Tag(name = "dlq-admin", description = "insight-api DLQ 운영 — internal-auth 호출자만.")
@RestController
@RequestMapping("/internal/admin/dlq")
class DlqAdminController(
    private val adminUseCases: DlqAdminUseCases,
) {

    @Operation(summary = "DLQ row 목록 (최근 순)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "internal-auth 누락", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(description = "PENDING | RESOLVED | DEAD. 미지정 = 전체.")
        @RequestParam(required = false) status: DlqStatus?,
        @Parameter(description = "PARSE | INGEST. 미지정 = 전체.")
        @RequestParam(required = false) kind: DlqFailureKind?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DlqRowResponse> = adminUseCases.list(status, kind, limit).map { DlqRowResponse.from(it) }

    @Operation(
        summary = "DLQ row 재시도",
        description = "DEAD/PENDING → PENDING + attempts=0. PARSE 도 retry 가능 (코드 수정 후 같은 payload 통과 케이스). RESOLVED 면 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재시도 예약"),
        ApiResponse(responseCode = "404", description = "row 없음", content = [Content()]),
        ApiResponse(responseCode = "409", description = "이미 RESOLVED", content = [Content()]),
    )
    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: Long): DlqRowResponse =
        DlqRowResponse.from(adminUseCases.retry(id))

    @Operation(summary = "DLQ row 삭제")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = adminUseCases.delete(id)
}

data class DlqRowResponse(
    val id: Long,
    val rawPayload: String,
    val idempotencyKey: String?,
    val failureKind: DlqFailureKind,
    val status: DlqStatus,
    val attempts: Int,
    val lastError: String,
    val failedAt: Instant,
    val nextRetryAt: Instant,
    val resolvedAt: Instant?,
) {
    companion object {
        fun from(e: ActivityEventDlqEntity) = DlqRowResponse(
            id = e.id!!,
            rawPayload = e.rawPayload,
            idempotencyKey = e.idempotencyKey,
            failureKind = e.failureKind,
            status = e.status,
            attempts = e.attempts,
            lastError = e.lastError,
            failedAt = e.failedAt,
            nextRetryAt = e.nextRetryAt,
            resolvedAt = e.resolvedAt,
        )
    }
}
