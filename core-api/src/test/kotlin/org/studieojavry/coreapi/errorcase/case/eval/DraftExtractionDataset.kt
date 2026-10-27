package org.studieojavry.coreapi.errorcase.case.eval

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity

/**
 * 에러 자동 구조화 **eval 데이터셋** — 라벨링된 샘플 로그 + 정답(ground truth).
 *
 * 공정 평가를 위해 정답을 집합으로 둔다:
 *  - `acceptableExceptionClasses`: 허용 예외 simple name(소문자). 빈 집합 = "예외 없음(null 기대)".
 *    (Caused-by 처럼 바깥/루트 둘 다 정답일 수 있는 경우 둘 다 넣는다)
 *  - `titleAnyOf`: 제목에 이 중 **하나라도** 포함되면 적중(동의어·한국어 포함). 빈 집합 = 미채점.
 *  - `acceptableTags`: 사람이 수용할 만한 태그 집합(예측 태그의 precision 채점용).
 */
data class EvalSample(
    val id: String,
    val paste: String,
    val acceptableExceptionClasses: Set<String>,
    val titleAnyOf: Set<String>,
    val expectedSeverity: Severity?,
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
            acceptableExceptionClasses = setOf("nullpointerexception"),
            titleAnyOf = setOf("nullpointer", "npe", "널"),
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("npe", "nullpointerexception", "java", "order", "orderservice", "ordercontroller", "kotlin", "null"),
        ),
        EvalSample(
            id = "kotlin-npe-service",
            paste = """
                Exception in thread "http-nio-8080-exec-3" java.lang.NullPointerException
                ${"\t"}at com.shop.OrderService.calculateTotal(OrderService.kt:88)
            """.trimIndent(),
            acceptableExceptionClasses = setOf("nullpointerexception"),
            titleAnyOf = setOf("nullpointer", "npe", "널"),
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("npe", "nullpointerexception", "kotlin", "order", "orderservice", "calculatetotal"),
        ),
        EvalSample(
            id = "oom-heap",
            paste = """
                java.lang.OutOfMemoryError: Java heap space
                ${"\t"}at java.base/java.util.Arrays.copyOf(Arrays.java:3537)
                ${"\t"}at com.report.PdfExporter.render(PdfExporter.java:120)
            """.trimIndent(),
            acceptableExceptionClasses = setOf("outofmemoryerror"),
            titleAnyOf = setOf("outofmemory", "oom", "메모리", "heap"),
            expectedSeverity = Severity.S1,
            acceptableTags = setOf("oom", "outofmemoryerror", "jvm", "heap", "heap-space", "memory", "pdf", "pdfexporter", "report"),
        ),
        EvalSample(
            id = "caused-by-timeout",
            paste = """
                org.springframework.web.client.ResourceAccessException: I/O error
                ${"\t"}at org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java:819)
                Caused by: java.net.SocketTimeoutException: Read timed out
                ${"\t"}at java.base/java.net.SocketInputStream.read(SocketInputStream.java:183)
            """.trimIndent(),
            // 바깥(ResourceAccessException)·루트(SocketTimeoutException) 둘 다 정답 인정
            acceptableExceptionClasses = setOf("resourceaccessexception", "sockettimeoutexception"),
            titleAnyOf = setOf("timeout", "타임아웃", "socket", "i/o"),
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("timeout", "sockettimeoutexception", "resourceaccessexception", "network", "http", "resttemplate", "socket", "spring", "io"),
        ),
        EvalSample(
            id = "hikari-pool-timeout",
            paste = """
                2026-10-26 11:02:13.551 ERROR 1 --- [http-nio-8080-exec-9] o.h.engine.jdbc.spi.SqlExceptionHelper :
                java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
                ${"\t"}at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
            """.trimIndent(),
            acceptableExceptionClasses = setOf("sqltransientconnectionexception"),
            titleAnyOf = setOf("connection", "커넥션", "timeout", "hikari", "pool"),
            expectedSeverity = Severity.S1,
            acceptableTags = setOf("hikaricp", "timeout", "database", "db", "503", "sqltransientconnectionexception", "jdbc", "connection-pool", "sqlexception", "pool", "connection"),
        ),
        EvalSample(
            id = "illegal-state",
            paste = """
                java.lang.IllegalStateException: Cannot publish an unresolved case
                ${"\t"}at com.ea.publish.PublishService.publish(PublishService.kt:55)
            """.trimIndent(),
            acceptableExceptionClasses = setOf("illegalstateexception"),
            titleAnyOf = setOf("illegalstate", "상태", "state", "publish", "발행"),
            expectedSeverity = Severity.S3,
            acceptableTags = setOf("illegalstateexception", "publish", "state", "publishservice", "unresolved-case", "case"),
        ),
        EvalSample(
            // 스택트레이스가 아닌 게이트웨이 로그 — 예외 없음(null 기대). title 미채점.
            id = "cors-403-log",
            paste = """
                2026-10-26 11:05:00 WARN  gateway - 403 Invalid CORS request on POST /api/v1/login
                origin=https://evil.example.com not allowed
            """.trimIndent(),
            acceptableExceptionClasses = emptySet(),
            titleAnyOf = emptySet(),
            expectedSeverity = Severity.S3,
            acceptableTags = setOf("cors", "403", "gateway", "security", "login", "auth"),
        ),
        EvalSample(
            // 비-JVM(Node) 에러 — JVM 정규식 밖. 정답 TypeError.
            id = "node-typeerror",
            paste = """
                TypeError: Cannot read properties of undefined (reading 'total')
                    at calcTotal (/app/order.js:42:19)
                    at /app/server.js:88:5
            """.trimIndent(),
            acceptableExceptionClasses = setOf("typeerror"),
            titleAnyOf = setOf("typeerror", "undefined"),
            expectedSeverity = Severity.S2,
            acceptableTags = setOf("typeerror", "node", "javascript", "js", "order", "undefined"),
        ),
    )
}
