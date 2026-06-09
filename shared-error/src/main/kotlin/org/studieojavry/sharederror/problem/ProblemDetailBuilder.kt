package org.studieojavry.sharederror.problem

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI
import java.time.Instant

/**
 * RFC 9457 Enriched ProblemDetail 빌더.
 *
 * 기본 5 필드 + 프로젝트 extension (code / timestamp / traceId / retryable / errors).
 */
object ProblemDetailBuilder {

    fun build(
        status: HttpStatus,
        detail: String,
        code: String,
        traceId: String,
        retryable: Boolean,
        instance: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(status, detail)
        pd.type = ErrorTypeUri.of(code)
        if (instance != null) pd.instance = URI.create(instance)
        pd.setProperty("code", code)
        pd.setProperty("timestamp", Instant.now().toString())
        pd.setProperty("traceId", traceId)
        pd.setProperty("retryable", retryable)
        extra.forEach { (k, v) -> pd.setProperty(k, v) }
        return pd
    }
}
