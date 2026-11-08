package org.studieojavry.coreapi.errorcase.sentry.presentation.web

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
import org.studieojavry.coreapi.errorcase.sentry.application.usecase.ReceiveSentryWebhookUseCase
import org.studieojavry.coreapi.errorcase.sentry.application.usecase.SentryWebhookSignatureException
import org.studieojavry.coreapi.errorcase.sentry.config.ConditionalOnSentryEnabled

/**
 * Sentry 웹훅 수신 — 앱 인증이 없는 공개 경로(게이트웨이 permitAll + per-IP rate limit).
 * 본문은 `ByteArray` 로 받는다: 서명은 원본 바이트 기준이라 JSON 으로 바인딩하면 검증할 수 없다.
 */
@Tag(name = "sentry-integration")
@RestController
@RequestMapping("/api/v1/integrations/sentry")
@ConditionalOnSentryEnabled
class SentryWebhookController(
    private val receiveWebhook: ReceiveSentryWebhookUseCase,
) {

    @Operation(
        summary = "Sentry 웹훅 수신 (Sentry 전용)",
        description = "Sentry-Hook-Signature 검증 후 받은 편지함에 적재하고 즉시 202. 처리는 비동기. " +
            "같은 본문 재전송은 무시(200).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "적재됨"),
        ApiResponse(responseCode = "200", description = "중복 전달 — 무시"),
        ApiResponse(responseCode = "400", description = "필수 헤더 누락"),
        ApiResponse(responseCode = "401", description = "서명 불일치"),
    )
    @PostMapping("/webhook")
    fun receive(
        @Parameter(description = "리소스 종류(issue 등)") @RequestHeader("Sentry-Hook-Resource") resource: String,
        @Parameter(description = "HMAC-SHA256(hex) 서명") @RequestHeader("Sentry-Hook-Signature", required = false) signature: String?,
        @RequestBody body: ByteArray,
    ): ResponseEntity<Void> {
        if (resource.isBlank() || resource.length > 40) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Sentry webhook headers")
        }
        val outcome = try {
            receiveWebhook.invoke(resource, signature, body)
        } catch (e: SentryWebhookSignatureException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, e.message, e)
        }
        return when (outcome) {
            ReceiveSentryWebhookUseCase.Outcome.ACCEPTED -> ResponseEntity.accepted().build()
            ReceiveSentryWebhookUseCase.Outcome.DUPLICATE -> ResponseEntity.ok().build()
        }
    }
}
