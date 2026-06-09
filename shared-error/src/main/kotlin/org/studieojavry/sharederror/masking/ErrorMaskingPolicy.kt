package org.studieojavry.sharederror.masking

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * prod: 5xx 의 detail 을 generic 메시지로 치환 (traceId 만 노출).
 * local / dev / stg: 원본 메시지 + Sanitizer 자동 마스킹.
 */
@Component
class ErrorMaskingPolicy(private val env: Environment) {

    fun maskedDetailFor5xx(ex: Throwable, traceId: String): String {
        if (env.activeProfiles.any { it == "prod" }) {
            return "Internal error. Reference: $traceId"
        }
        val raw = ex.message ?: ex::class.simpleName ?: "unknown"
        return Sanitizer.sanitize(raw) ?: raw
    }
}
