package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import java.time.Instant

/**
 * GitHub 웹훅 받은 편지함 운영 도구. `/internal` 경로 → gateway 가 외부로 열지 않고, internal-auth 토큰이 있어야 호출된다.
 *
 * 사용 시점: GitHub 장애·코드 버그로 FAILED 가 쌓임 → list 로 원인(lastError) 확인 → 원인 해소 후 retry / retry-failed.
 * payload 는 목록에 싣지 않는다(크고, 비공개 저장소 내용이 들어 있을 수 있음).
 */
@Tag(name = "github-webhook-admin", description = "GitHub 웹훅 받은 편지함 운영 — internal-auth 보유 호출자만.")
@RestController
@RequestMapping("/internal/admin/github/webhooks")
@ConditionalOnGithubEnabled
class GithubWebhookAdminController(
    private val useCases: GithubWebhookAdminUseCases,
) {

    @Operation(summary = "웹훅 목록", description = "최근 수신순. status 미지정 = 전체. payload 는 싣지 않는다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "internal-auth 누락", content = [Content()]),
    )
    @GetMapping
    fun list(
        @Parameter(description = "필터: PENDING | DONE | SKIPPED | FAILED. 미지정 = 전체.")
        @RequestParam(required = false) status: InboxStatus?,
        @Parameter(description = "최대 행 수 (1~500).")
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<GithubWebhookRowResponse> = useCases.list(status, limit).map(GithubWebhookRowResponse::from)

    @Operation(
        summary = "웹훅 한 건 재처리",
        description = "FAILED·PENDING 을 시도 횟수 0 으로 되돌려 다음 처리 주기에 다시 처리. " +
            "이미 끝난 DONE·SKIPPED 는 force=true 일 때만(처리는 멱등이라 다시 적용해도 중복 기록 없음).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재처리 대기열로 되돌림"),
        ApiResponse(responseCode = "404", description = "없는 delivery", content = [Content()]),
        ApiResponse(responseCode = "409", description = "이미 처리됨(DONE·SKIPPED) — force=true 필요", content = [Content()]),
    )
    @PostMapping("/{deliveryId}/retry")
    fun retry(
        @Parameter(description = "X-GitHub-Delivery 값") @PathVariable deliveryId: String,
        @Parameter(description = "이미 처리된 건도 다시 처리") @RequestParam(defaultValue = "false") force: Boolean,
    ): GithubWebhookRowResponse = try {
        GithubWebhookRowResponse.from(useCases.retry(deliveryId, force))
    } catch (e: GithubWebhookAdminUseCases.AlreadyProcessedException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
    }

    @Operation(summary = "FAILED 전부 재처리", description = "장애가 풀린 뒤 FAILED 를 한 번에 시도 횟수 0 으로 되돌림. 응답에 되돌린 건수.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "되돌림"))
    @PostMapping("/retry-failed")
    fun retryFailed(): RetryFailedResponse = RetryFailedResponse(useCases.retryAllFailed())
}

data class GithubWebhookRowResponse(
    val deliveryId: String,
    val event: String,
    val action: String?,
    val installationId: Long?,
    val status: InboxStatus,
    val attempts: Int,
    val lastError: String?,
    val receivedAt: Instant,
    val processedAt: Instant?,
    val nextRetryAt: Instant,
) {
    companion object {
        fun from(e: GithubWebhookInboxEntity) = GithubWebhookRowResponse(
            e.deliveryId, e.event, e.action, e.installationId, e.status, e.attempts, e.lastError,
            e.receivedAt, e.processedAt, e.nextRetryAt,
        )
    }
}

data class RetryFailedResponse(val requeued: Int)
