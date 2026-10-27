package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubWebhookSignatureException
import org.studieojavry.coreapi.errorcase.github.application.usecase.ReceiveGithubWebhookUseCase
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled

/**
 * GitHub 웹훅 수신 — 앱 JWT 가 없는 공개 경로(게이트웨이 permitAll + per-IP rate limit).
 * 본문은 `ByteArray` 로 받는다: 서명은 원본 바이트 기준이라 JSON 으로 바인딩하면 검증할 수 없다.
 */
@Tag(name = "github-integration")
@RestController
@RequestMapping("/api/v1/integrations/github")
@ConditionalOnGithubEnabled
class GithubWebhookController(
    private val receiveWebhook: ReceiveGithubWebhookUseCase,
) {

    @Operation(
        summary = "GitHub 웹훅 수신 (GitHub 전용)",
        description = "X-Hub-Signature-256 검증 후 받은 편지함에 적재하고 즉시 202. 처리는 비동기. " +
            "같은 X-GitHub-Delivery 재전송은 무시(200).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "적재됨"),
        ApiResponse(responseCode = "200", description = "중복 전달 — 무시"),
        ApiResponse(responseCode = "400", description = "필수 헤더 누락"),
        ApiResponse(responseCode = "401", description = "서명 불일치"),
    )
    @PostMapping("/webhook")
    fun receive(
        @Parameter(description = "이벤트 종류") @RequestHeader("X-GitHub-Event") event: String,
        @Parameter(description = "전달 id (멱등 키)") @RequestHeader("X-GitHub-Delivery") deliveryId: String,
        @Parameter(description = "sha256=<hex> HMAC") @RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
        @RequestBody body: ByteArray,
    ): ResponseEntity<Void> {
        if (deliveryId.isBlank() || deliveryId.length > 64 || event.isBlank() || event.length > 40) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid GitHub webhook headers")
        }
        val outcome = try {
            receiveWebhook.invoke(event, deliveryId, signature, body)
        } catch (e: GithubWebhookSignatureException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, e.message, e)
        }
        return when (outcome) {
            ReceiveGithubWebhookUseCase.Outcome.ACCEPTED -> ResponseEntity.accepted().build()
            ReceiveGithubWebhookUseCase.Outcome.DUPLICATE -> ResponseEntity.ok().build()
        }
    }
}
