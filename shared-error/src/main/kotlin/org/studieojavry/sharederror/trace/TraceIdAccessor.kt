package org.studieojavry.sharederror.trace

import io.micrometer.tracing.Tracer
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Micrometer Tracing 의 현재 span 에서 traceId 추출.
 * 없으면 MDC → 그래도 없으면 UUID fallback (예: 인증 filter 전 단계 등).
 */
@Component
class TraceIdAccessor(private val tracer: Tracer?) {

    fun currentOrNew(): String =
        tracer?.currentSpan()?.context()?.traceId()
            ?: MDC.get("traceId")
            ?: UUID.randomUUID().toString()
}
