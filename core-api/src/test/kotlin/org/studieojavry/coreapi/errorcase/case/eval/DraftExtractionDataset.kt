package org.studieojavry.coreapi.errorcase.case.eval

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity

/**
 * 에러 자동 구조화 **eval 데이터셋** — 라벨링된 샘플 로그 + 정답(ground truth).
 *
 * 같은 하네스로 ① 정규식(결정적) 경로 베이스라인, ② 실제 LLM 경로(키 설정 시)를 측정한다.
 * exceptionClass 는 simple name 기준으로 채점(FQN 유무 무관). tags/severity 는 LLM 이 있을 때 의미.
 */
data class EvalSample(
    val id: String,
    val paste: String,
    /** 정답 예외 클래스(없을 수 있음 — 스택트레이스 아닌 로그). simple name 으로 채점. */
    val expectedExceptionClass: String?,
    /** 제목에 반드시 들어가야 할 키워드(대소문자 무관). null 이면 미채점. */
    val titleMustContain: String?,
    /** 정답 심각도(LLM 평가용). null 이면 미채점. */
    val expectedSeverity: Severity?,
    /** 허용 태그 집합(LLM 평가용 — 예측 태그가 이 안에 들면 맞음). */
    val acceptableTags: Set<String>,
)

object DraftExtractionDataset {
    val samples: List<EvalSample> = listOf(
        EvalSample(
            id = "java-npe",
            paste = """
                java.lang.NullPointerException: coupon is null
                ${"\t"}at com.shop.OrderService.calculateTotal(OrderService.java:42)
                ${"\t"}at com.shop.OrderController.create(OrderController.java:17)
            """.trimIndent(),
            expectedExceptionClass = "NullPointerException",
            titleMustContain = "NullPointer",
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("npe", "nullpointerexception", "order", "java"),
        ),
        EvalSample(
            id = "kotlin-npe-service",
            paste = """
                Exception in thread "http-nio-8080-exec-3" java.lang.NullPointerException
                ${"\t"}at com.shop.OrderService.calculateTotal(OrderService.kt:88)
            """.trimIndent(),
            expectedExceptionClass = "NullPointerException",
            titleMustContain = "NullPointer",
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("npe", "kotlin", "order"),
        ),
        EvalSample(
            id = "oom-heap",
            paste = """
                java.lang.OutOfMemoryError: Java heap space
                ${"\t"}at java.base/java.util.Arrays.copyOf(Arrays.java:3537)
                ${"\t"}at com.report.PdfExporter.render(PdfExporter.java:120)
            """.trimIndent(),
            expectedExceptionClass = "OutOfMemoryError",
            titleMustContain = "OutOfMemory",
            expectedSeverity = Severity.S1,
            acceptableTags = setOf("oom", "outofmemoryerror", "jvm", "heap"),
        ),
        EvalSample(
            id = "caused-by-timeout",
            paste = """
                org.springframework.web.client.ResourceAccessException: I/O error
                ${"\t"}at org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java:819)
                Caused by: java.net.SocketTimeoutException: Read timed out
                ${"\t"}at java.base/java.net.SocketInputStream.read(SocketInputStream.java:183)
            """.trimIndent(),
            expectedExceptionClass = "SocketTimeoutException",
            titleMustContain = "Timeout",
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("timeout", "sockettimeoutexception", "network", "http"),
        ),
        EvalSample(
            id = "hikari-pool-timeout",
            paste = """
                2026-10-26 11:02:13.551 ERROR 1 --- [http-nio-8080-exec-9] o.h.engine.jdbc.spi.SqlExceptionHelper :
                java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
                ${"\t"}at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
            """.trimIndent(),
            expectedExceptionClass = "SQLTransientConnectionException",
            titleMustContain = "Connection",
            expectedSeverity = Severity.S1,
            acceptableTags = setOf("hikaricp", "timeout", "database", "503", "sqltransientconnectionexception"),
        ),
        EvalSample(
            id = "illegal-state",
            paste = """
                java.lang.IllegalStateException: Cannot publish an unresolved case
                ${"\t"}at com.ea.publish.PublishService.publish(PublishService.kt:55)
            """.trimIndent(),
            expectedExceptionClass = "IllegalStateException",
            titleMustContain = "IllegalState",
            expectedSeverity = Severity.S3,
            acceptableTags = setOf("illegalstateexception", "publish", "state"),
        ),
        EvalSample(
            // 스택트레이스가 아닌 '게이트웨이 로그' — 정답 예외 클래스 없음(null). 정규식·LLM 모두 null 이어야.
            id = "cors-403-log",
            paste = """
                2026-10-26 11:05:00 WARN  gateway - 403 Invalid CORS request on POST /api/v1/login
                origin=https://evil.example.com not allowed
            """.trimIndent(),
            expectedExceptionClass = null,
            titleMustContain = null,
            expectedSeverity = Severity.S3,
            acceptableTags = setOf("cors", "403", "gateway", "security"),
        ),
        EvalSample(
            // 비-JVM(Node) 에러 — JVM 정규식은 대개 못 잡음(→ LLM 가치 구간). 정답은 TypeError.
            id = "node-typeerror",
            paste = """
                TypeError: Cannot read properties of undefined (reading 'total')
                    at calcTotal (/app/order.js:42:19)
                    at /app/server.js:88:5
            """.trimIndent(),
            expectedExceptionClass = "TypeError",
            titleMustContain = "TypeError",
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("typeerror", "node", "javascript", "order"),
        ),
    )
}
