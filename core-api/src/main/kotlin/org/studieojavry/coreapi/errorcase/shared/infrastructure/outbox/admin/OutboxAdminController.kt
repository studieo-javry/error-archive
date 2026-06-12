package org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.admin

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
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxEventEntity
import org.studieojavry.coreapi.errorcase.shared.infrastructure.outbox.OutboxStatus
import java.time.Instant

/**
 * Outbox 운영 도구. `/internal` 경로 → shared-internal-auth 필터로 보호 (X-Internal-Auth 토큰 필수).
 *
 * 사용 시점:
 *  - DEAD row 발견 (Micrometer alert) → list → 원인 분석 → retry 또는 delete.
 *  - SENT 누적 디버깅 → cleanup-sent.
 */
@Tag(name = "outbox-admin", description = "Outbox 운영 — internal-auth 보유 호출자만.")
@RestController
@RequestMapping("/internal/admin/outbox")
class OutboxAdminController(
    private val adminUseCases: OutboxAdminUseCases,
) {

    @Operation(
        summary = "Outbox row 목록",
        description = "최근 등록 순 (id DESC). status 미지정 = 전체.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "internal-auth 누락", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(description = "필터: PENDING | SENT | DEAD. 미지정 = 전체.")
        @RequestParam(required = false) status: OutboxStatus?,
        @Parameter(description = "최대 row 수 (1~500).")
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<OutboxRowResponse> = adminUseCases.list(status, limit).map { OutboxRowResponse.from(it) }

    @Operation(
        summary = "Outbox row 재시도",
        description = "DEAD 또는 정체된 PENDING 을 즉시 재시도 대기열로. attempts=0 으로 리셋.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재시도 예약됨"),
        ApiResponse(responseCode = "404", description = "row 없음", content = [Content()]),
        ApiResponse(responseCode = "409", description = "이미 SENT", content = [Content()]),
    )
    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: Long): OutboxRowResponse =
        OutboxRowResponse.from(adminUseCases.retry(id))

    @Operation(summary = "Outbox row 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제됨"),
        ApiResponse(responseCode = "404", description = "row 없음", content = [Content()]),
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = adminUseCases.delete(id)

    @Operation(
        summary = "SENT TTL 만료분 즉시 정리",
        description = "cron 대기 없이 운영자가 강제 트리거. 응답에 삭제된 row 수.",
    )
    @PostMapping("/cleanup-sent")
    fun cleanupSent(): CleanupResponse = CleanupResponse(deleted = adminUseCases.cleanupSent())
}

data class OutboxRowResponse(
    val id: Long,
    val aggregateType: String,
    val aggregateId: String,
    val topic: String,
    val kafkaKey: String?,
    val status: OutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val nextRetryAt: Instant,
) {
    companion object {
        fun from(e: OutboxEventEntity) = OutboxRowResponse(
            id = e.id!!,
            aggregateType = e.aggregateType,
            aggregateId = e.aggregateId,
            topic = e.topic,
            kafkaKey = e.kafkaKey,
            status = e.status,
            attempts = e.attempts,
            lastError = e.lastError,
            createdAt = e.createdAt,
            sentAt = e.sentAt,
            nextRetryAt = e.nextRetryAt,
        )
    }
}

data class CleanupResponse(val deleted: Int)
