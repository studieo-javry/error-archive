package org.studieojavry.publishapi.demo.application

/**
 * 데모 워크스페이스 발행물 시드 콘텐츠 (10건).
 *
 * publish-api 전용 — core 케이스를 스냅샷하지 않고 **손수 작성한 풍성한 스냅샷**을 직접 발행한다.
 * (워크스페이스 데모 케이스는 2~3스텝이라 발행물 품질 기준(errorCaseId=4: 6스텝+FAILED 시도)에 못 미침.)
 *
 * ⚠️ 발행물 HTML 렌더러(HtmlRenderer) 제약 — 이 범위 안에서만 작성:
 *   - 지원: 문단, ```lang 코드펜스```(하이라이팅), `> 인용`, 인라인 `code`, 스니펫 블록, FAILED/RESOLVED 스텝.
 *   - 미지원: `#` 헤딩, `**굵게**`, `- ` 목록(리터럴 렌더). → 헤딩/굵게 쓰지 말 것.
 *
 * 각 케이스는 6스텝(일부 FAILED 막다른 길 포함) + 솔루션으로 구성. 테마는 실제 코드베이스 이슈 기반.
 */

/** 스니펫(코드블록) — HtmlRenderer 가 상단 언어 탭 + 신호등으로 렌더. */
data class WsSnippet(
    val title: String?,
    val language: String,
    val code: String,
    val caption: String? = null,
    val filePathOrClass: String? = null,
)

/** 한 스텝 — outcome 은 RESOLVED / FAILED / IN_PROGRESS (타임라인 아이콘·색 결정). */
data class WsStep(
    val title: String,
    val outcome: String,
    val body: String,
    val durationMinutes: Int? = null,
    val snippet: WsSnippet? = null,
)

data class WsSolution(
    val title: String,
    val body: String,
    val snippet: WsSnippet? = null,
)

data class WsCase(
    val title: String,
    val author: String,
    val summary: String,
    val tags: List<String>,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val stackTrace: String?,
    val fingerprint: String?,
    val description: String,
    val steps: List<WsStep>,
    val solution: WsSolution,
)

internal object WsOutcome {
    const val RESOLVED = "RESOLVED"
    const val FAILED = "FAILED"
    const val WIP = "IN_PROGRESS"
}

/** 데모 워크스페이스 발행물 시드 — 순서가 곧 originalCaseId 오프셋(1..N). */
internal val WORKSPACE_PUBLISHMENT_SEED: List<WsCase> = buildList {

    // 1 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "피크 시간마다 터지는 503 — HikariCP 커넥션 풀 고갈 추적기",
            author = "김도현 · 백엔드",
            summary = "점심·저녁 트래픽 피크에만 503 이 번졌다. 느린 쿼리가 아니라 **스트림 응답에서 커넥션을 반납하지 않는** 누수가 풀을 말려 죽인 사건.",
            tags = listOf("hikaricp", "connection-pool", "timeout", "jpa"),
            exceptionClass = "org.springframework.dao.DataAccessResourceFailureException",
            exceptionMessage = "Unable to acquire JDBC Connection; HikariPool-1 - Connection is not available, request timed out after 30000ms",
            stackTrace = """
                org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
                    at o.s.orm.jpa.vendor.HibernateJpaDialect.convertHibernateAccessException
                    at o.s.orm.jpa.AbstractEntityManagerFactoryBean.translateExceptionIfPossible
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException
                    at com.zaxxer.hikari.pool.HikariPool.getConnection
            """.trimIndent(),
            fingerprint = "b1f3c9a2",
            description = """
                피크(12:00, 18:00) 때마다 `503 Service Unavailable` 이 특정 노드에서 번졌다. CPU·메모리는 여유로운데
                요청이 30초를 꽉 채우고 죽는 게 단서였다. 30초 = HikariCP `connection-timeout` 기본값. 즉 쿼리가 느린 게
                아니라 **커넥션을 못 받고 대기하다 타임아웃** 난 것.

                > 느린 쿼리를 찾지 말고, 반납되지 않는 커넥션을 찾아라.

                풀 크기는 10. 피크에 active=10 / idle=0 / pending=24 로 고정돼 있었다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "메트릭으로 증상 범위 좁히기", WsOutcome.RESOLVED,
                    """
                    `hikaricp.connections.pending` 를 대시보드에 올리니 피크마다 치솟고 안 내려왔다. active 가 max(10)에
                    붙박이. 반납이 안 되고 있다는 뜻. 어떤 요청이 커넥션을 오래 쥐는지부터 봤다.
                    """.trimIndent(),
                    durationMinutes = 40,
                    snippet = WsSnippet(
                        "노출한 풀 메트릭", "properties",
                        "management.metrics.enable.hikaricp=true\n# pending 이 0 으로 안 떨어지면 반납 누수 신호",
                    ),
                ),
                WsStep(
                    "느린 쿼리 튜닝 — 막다른 길", WsOutcome.FAILED,
                    """
                    처음엔 느린 쿼리 탓이라 보고 `pg_stat_statements` 로 상위 쿼리에 인덱스를 추가했다. p95 쿼리 시간은
                    40ms→12ms 로 좋아졌지만 503 은 그대로. 커넥션이 *쿼리 시간*이 아니라 *요청 수명 내내* 잡혀 있었기
                    때문. 방향이 틀렸다.
                    """.trimIndent(),
                    durationMinutes = 90,
                ),
                WsStep(
                    "leakDetectionThreshold 로 누수 지점 특정", WsOutcome.RESOLVED,
                    """
                    `leak-detection-threshold` 를 2초로 걸자 로그에 누수 스택이 찍혔다. 전부 CSV 내보내기 엔드포인트.
                    `Stream<Entity>` 로 결과를 흘려보내는데, 응답을 다 쓰기 전엔 커넥션이 안 돌아온다.
                    """.trimIndent(),
                    durationMinutes = 25,
                    snippet = WsSnippet(
                        "누수 탐지 로그", "text",
                        "Connection leak detection triggered for conn ... on thread http-nio-8080-exec-7\n"
                            + "  at c.e.export.CaseExportService.streamToCsv(CaseExportService.kt:48)",
                    ),
                ),
                WsStep(
                    "스트리밍 응답과 트랜잭션 경계 분석", WsOutcome.RESOLVED,
                    """
                    `@Transactional` 메서드가 `Stream` 을 반환하면 스트림을 소비하는 동안 트랜잭션(=커넥션)이 열린 채다.
                    대용량 CSV 는 소비에 수 초가 걸려 피크에 10개 커넥션이 전부 export 로 묶였다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "fetchSize + 명시적 커서 닫기 적용", WsOutcome.RESOLVED,
                    """
                    스트림을 `use { }` 로 감싸 반드시 닫고, JDBC `fetchSize` 를 1000 으로 줘 힙·커넥션 점유를 줄였다.
                    export 는 읽기 전용 커넥션 풀(별도 10개)로 분리해 일반 요청과 격리.
                    """.trimIndent(),
                    durationMinutes = 50,
                    snippet = WsSnippet(
                        "스트림 반드시 닫기", "kotlin",
                        "repository.streamAll().use { rows ->\n    rows.forEach { writer.write(it.toCsvLine()) }\n}  // use → 커넥션 반납 보장",
                        filePathOrClass = "CaseExportService.kt",
                    ),
                ),
                WsStep(
                    "피크 재현 부하 테스트로 검증", WsOutcome.RESOLVED,
                    """
                    k6 로 피크 2배 부하를 걸어도 `pending` 이 0 근처에서 안정. 503 소멸. export 전용 풀만 바빠지고 일반
                    트래픽은 영향 없음.
                    """.trimIndent(),
                    durationMinutes = 35,
                ),
            ),
            solution = WsSolution(
                "스트리밍 응답의 커넥션은 use/try-with 로 반납을 강제하고, export 풀을 분리한다",
                "근본 원인은 느린 쿼리가 아니라 `Stream` 응답이 커넥션을 요청 수명 내내 잡은 누수였다. "
                    + "`use { }` 로 반납을 보장하고 export 를 별도 읽기 풀로 격리하자 피크 503 이 사라졌다.",
                WsSnippet(
                    "export 전용 DataSource 분리", "kotlin",
                    "@Bean @Qualifier(\"exportDs\")\nfun exportDataSource(): DataSource =\n    HikariDataSource(cfg.apply { maximumPoolSize = 10; poolName = \"export-pool\" })",
                ),
            ),
        ),
    )

    // 2 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "PDF 공개 export 중 OutOfMemoryError — 전부 메모리에 올리던 렌더러",
            author = "이서준 · 백엔드",
            summary = "큰 발행물을 PDF 로 받으면 노드가 통째로 죽었다. 원인은 HTML→PDF 를 **바이트 배열로 전부 버퍼링**한 것. 스트리밍으로 바꿔 상수 메모리로.",
            tags = listOf("pdf", "oom", "heap", "streaming"),
            exceptionClass = "java.lang.OutOfMemoryError",
            exceptionMessage = "Java heap space",
            stackTrace = """
                java.lang.OutOfMemoryError: Java heap space
                    at java.base/java.util.Arrays.copyOf(Arrays.java:3745)
                    at java.base/java.io.ByteArrayOutputStream.toByteArray(ByteArrayOutputStream.java:216)
                    at c.e.publish.PdfRenderer.render(PdfRenderer.kt:61)
            """.trimIndent(),
            fingerprint = "7c20ae51",
            description = """
                공개 페이지 PDF 다운로드가 특정 발행물에서만 노드 전체를 OOM 으로 떨어뜨렸다. 힙 덤프를 보니
                단일 `byte[]` 가 480MB. 발행물 하나를 렌더하는데 왜 이렇게 큰가 — 이미지 첨부가 많은 글이었다.

                > "가끔 죽는다" 가 아니라 "큰 입력에서 선형으로 죽는다" 면 버퍼링을 의심하라.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "힙 덤프로 거대 객체 식별", WsOutcome.RESOLVED,
                    """
                    `-XX:+HeapDumpOnOutOfMemoryError` 로 덤프를 받아 Eclipse MAT 로 열었다. Dominator tree 최상단이
                    `ByteArrayOutputStream` 하나. PDF 전체를 메모리에 모았다가 한 번에 응답에 복사하는 구조였다.
                    """.trimIndent(),
                    durationMinutes = 45,
                ),
                WsStep(
                    "힙만 키우기 — 임시방편, 막다른 길", WsOutcome.FAILED,
                    """
                    `-Xmx` 를 2G→4G 로 올려 당장은 안 죽었지만, 더 큰 글에서 다시 터졌다. 선형으로 증가하는 메모리를
                    상수로 만들지 않는 한 한계를 미룰 뿐이었다. 롤백.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "PDF 라이브러리의 스트리밍 API 확인", WsOutcome.RESOLVED,
                    """
                    쓰던 렌더러가 `OutputStream` 으로 직접 쓰는 API 를 제공하는데, 우리 코드가 굳이 `toByteArray()` 로
                    모아서 반환하고 있었다. 응답 `OutputStream` 에 바로 흘리면 버퍼가 필요 없다.
                    """.trimIndent(),
                    durationMinutes = 25,
                    snippet = WsSnippet(
                        "문제의 버퍼링 코드", "kotlin",
                        "val baos = ByteArrayOutputStream()\nrenderer.createPDF(baos)      // 전체를 메모리에\nreturn baos.toByteArray()      // 480MB 복사 → OOM",
                        filePathOrClass = "PdfRenderer.kt",
                    ),
                ),
                WsStep(
                    "StreamingResponseBody 로 전환", WsOutcome.RESOLVED,
                    """
                    컨트롤러를 `StreamingResponseBody` 로 바꿔 렌더러가 응답 스트림에 직접 쓰게 했다. 이미지도 디스크
                    임시파일 → 스트림 순서로 처리해 피크 힙을 수십 MB 로 눌렀다.
                    """.trimIndent(),
                    durationMinutes = 60,
                    snippet = WsSnippet(
                        "스트리밍 응답", "kotlin",
                        "return StreamingResponseBody { out ->\n    renderer.createPDF(out)   // 응답 스트림에 바로\n}",
                    ),
                ),
                WsStep(
                    "대용량 입력 회귀 테스트 추가", WsOutcome.RESOLVED,
                    """
                    이미지 200장짜리 발행물을 고정 픽스처로 넣고, 렌더 중 피크 힙을 측정하는 테스트를 추가했다.
                    상한 128MB 를 넘으면 실패하도록.
                    """.trimIndent(),
                    durationMinutes = 40,
                ),
                WsStep(
                    "스테이징 부하로 최종 확인", WsOutcome.RESOLVED,
                    """
                    동시 10 PDF 다운로드에도 힙이 평탄. 가장 큰 발행물도 OOM 없이 완료. 응답 TTFB 도 버퍼링 제거로
                    오히려 빨라졌다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
            ),
            solution = WsSolution(
                "PDF 를 바이트 배열로 모으지 말고 응답 OutputStream 으로 스트리밍한다",
                "`toByteArray()` 버퍼링이 입력 크기에 비례해 힙을 먹어 OOM 을 냈다. `StreamingResponseBody` 로 "
                    + "응답 스트림에 바로 써서 메모리를 상수로 만들었다. 힙 증설은 문제를 미룰 뿐이었다.",
            ),
        ),
    )

    // 3 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "쿠폰 한도 초과 발급 — 멀티 인스턴스에서 깨진 재고 차감",
            author = "박하늘 · 백엔드",
            summary = "선착순 1000장 쿠폰이 1043장 발급됐다. 단일 인스턴스에선 맞던 `check-then-decrement` 가 **인스턴스가 늘자** 경쟁 상태로 샜다. 원자적 조건부 UPDATE 로 봉합.",
            tags = listOf("concurrency", "race-condition", "coupon", "postgresql"),
            exceptionClass = null,
            exceptionMessage = null,
            stackTrace = null,
            fingerprint = null,
            description = """
                이벤트 쿠폰이 한도(1000)를 넘겨 1043장 발급됐다. 예외는 없었다 — 조용히 틀린 값. 재고를 먼저 읽고
                애플리케이션에서 비교한 뒤 차감하는 전형적 `check-then-act` 였고, 노드가 3대로 늘면서 동시에 "아직
                남았다" 를 읽어버렸다.

                > 예외 없이 숫자만 틀리면, 가장 먼저 동시성을 의심한다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "발급 로그로 초과분 재구성", WsOutcome.RESOLVED,
                    """
                    발급 타임스탬프를 ms 단위로 정렬하니 1000번째 전후 40ms 안에 43건이 몰려 있었다. 같은 순간에 여러
                    요청이 통과 — 경쟁 상태의 교과서적 패턴.
                    """.trimIndent(),
                    durationMinutes = 35,
                ),
                WsStep(
                    "синхronized 로 막기 — 막다른 길", WsOutcome.FAILED,
                    """
                    서비스 메서드에 JVM `synchronized` 를 걸었더니 단위 테스트는 통과했지만 운영에선 여전히 초과됐다.
                    락이 *프로세스 내부*에만 유효해 3대 인스턴스 사이에선 무력했다. 분산 환경을 못 본 수정.
                    """.trimIndent(),
                    durationMinutes = 50,
                    snippet = WsSnippet(
                        "무력했던 로컬 락", "kotlin",
                        "@Synchronized\nfun issue(couponId: Long) {  // 이 JVM 안에서만 유효\n    val left = repo.remaining(couponId)\n    if (left > 0) repo.decrement(couponId)\n}",
                    ),
                ),
                WsStep(
                    "원자적 조건부 UPDATE 설계", WsOutcome.RESOLVED,
                    """
                    읽고-비교-쓰기를 DB 한 문장으로 합쳤다. `UPDATE ... SET remaining = remaining - 1 WHERE id = ? AND
                    remaining > 0`. 영향 행 수가 0 이면 품절. 경쟁은 DB 행 락이 직렬화.
                    """.trimIndent(),
                    durationMinutes = 40,
                    snippet = WsSnippet(
                        "원자적 차감", "sql",
                        "UPDATE coupon_stock\n   SET remaining = remaining - 1\n WHERE id = :id AND remaining > 0;\n-- affected == 0 → 품절",
                    ),
                ),
                WsStep(
                    "affected rows 로 발급 성공 판정", WsOutcome.RESOLVED,
                    """
                    JPA `@Modifying` 쿼리의 반환값(영향 행 수)으로 발급 성공/품절을 가렸다. 성공한 트랜잭션만 쿠폰
                    레코드를 insert.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "부하 테스트로 정확성 재현", WsOutcome.RESOLVED,
                    """
                    3 인스턴스 + 동시 5000 요청으로 한도 100 쿠폰을 재현. 정확히 100장, 0 초과. 수백 번 반복해도 안정.
                    """.trimIndent(),
                    durationMinutes = 45,
                ),
                WsStep(
                    "Redis 분산 락 대안 비교 후 보류", WsOutcome.WIP,
                    """
                    Redlock 도 검토했지만, 단일 재고 차감엔 DB 조건부 UPDATE 가 더 단순·안전(락 유실/스큐 없음)해 그걸
                    채택. 분산 락은 다중 리소스 조정이 필요할 때 다시 보기로.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
            ),
            solution = WsSolution(
                "check-then-decrement 를 원자적 조건부 UPDATE 한 문장으로 합친다",
                "경쟁 상태의 근원은 읽기와 쓰기 사이의 틈이었다. `WHERE remaining > 0` 조건부 UPDATE 로 차감을 원자화하고 "
                    + "영향 행 수로 품절을 판정하자 멀티 인스턴스에서도 한도를 정확히 지켰다. JVM 락은 분산에서 무의미했다.",
            ),
        ),
    )

    // 4 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "알림 SSE 연결이 약 30초마다 끊긴다 — 프록시 유휴 타임아웃과 하트비트",
            author = "정유나 · 플랫폼",
            summary = "실시간 알림 SSE 가 정확히 30초 주기로 끊기고 재연결했다. 범인은 **리버스 프록시 유휴 타임아웃**. 주기적 코멘트 하트비트로 연결을 살려 해결.",
            tags = listOf("sse", "timeout", "nginx", "realtime"),
            exceptionClass = "java.io.IOException",
            exceptionMessage = "Broken pipe",
            stackTrace = """
                java.io.IOException: Broken pipe
                    at java.base/sun.nio.ch.FileDispatcherImpl.write0(Native Method)
                    at o.a.catalina.connector.OutputBuffer.realWriteBytes
                    at c.e.noti.SseEmitterRegistry.send(SseEmitterRegistry.kt:72)
            """.trimIndent(),
            fingerprint = "e5d1b740",
            description = """
                브라우저 알림 SSE 가 30초마다 `onerror` → 재연결을 반복했다. 서버 로그엔 주기적 `Broken pipe`.
                30초라는 "딱 떨어지는 주기" 가 핵심 단서 — 애플리케이션이 아니라 중간 네트워크 장비의 타임아웃이다.

                > 주기가 일정하면 코드가 아니라 인프라 타이머를 보라.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "주기성으로 범위 좁히기", WsOutcome.RESOLVED,
                    """
                    끊김 간격이 항상 30±1초. 애플리케이션 타임아웃은 전부 60초 이상이라 용의선상에서 제외. 트래픽 경로의
                    nginx `proxy_read_timeout` 기본값이 정확히 그 값이었다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "서버 타임아웃만 늘리기 — 막다른 길", WsOutcome.FAILED,
                    """
                    Tomcat `asyncTimeout` 과 `SseEmitter` 타임아웃을 무제한으로 바꿨지만 30초 끊김은 그대로. 연결을
                    끊는 주체가 서버가 아니라 그 앞단 프록시였기 때문. 서버 쪽만 보면 안 보인다.
                    """.trimIndent(),
                    durationMinutes = 40,
                ),
                WsStep(
                    "프록시 설정 확인으로 주범 특정", WsOutcome.RESOLVED,
                    """
                    nginx 설정을 보니 SSE 경로에도 `proxy_read_timeout 30s` 가 상속됐다. 30초간 바이트가 없으면 upstream
                    연결을 닫는다. 알림이 뜸한 시간엔 30초간 아무 바이트도 안 흐른다.
                    """.trimIndent(),
                    durationMinutes = 25,
                    snippet = WsSnippet(
                        "SSE 경로 프록시 설정", "nginx",
                        "location /api/v1/notifications/stream {\n    proxy_buffering off;      # SSE 는 버퍼링 금지\n    proxy_read_timeout 1h;    # 유휴 타임아웃 완화\n}",
                    ),
                ),
                WsStep(
                    "하트비트(코멘트 프레임) 주기 전송", WsOutcome.RESOLVED,
                    """
                    프록시 설정에만 의존하지 않도록, 15초마다 SSE 코멘트(`: ping`)를 흘려 "살아있음" 바이트를 보냈다.
                    코멘트는 `EventSource` 가 무시하므로 클라이언트엔 영향 없음.
                    """.trimIndent(),
                    durationMinutes = 35,
                    snippet = WsSnippet(
                        "하트비트 스케줄러", "kotlin",
                        "@Scheduled(fixedRate = 15_000)\nfun heartbeat() = registry.all().forEach {\n    runCatching { it.send(SseEmitter.event().comment(\"ping\")) }\n}",
                    ),
                ),
                WsStep(
                    "끊긴 emitter 정리 로직 점검", WsOutcome.RESOLVED,
                    """
                    하트비트 전송 실패(`IOException`)를 끊김 신호로 삼아 레지스트리에서 즉시 제거. 죽은 emitter 가
                    쌓여 메모리를 먹던 부수 문제도 같이 해결.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "장시간 유휴 연결 검증", WsOutcome.RESOLVED,
                    """
                    알림 없는 상태로 2시간 연결 유지 — 끊김 0. 재연결 폭주로 인한 부하도 사라졌다.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
            ),
            solution = WsSolution(
                "프록시 유휴 타임아웃을 늘리고, 애플리케이션 하트비트로 연결을 능동적으로 살린다",
                "30초 주기 끊김은 nginx `proxy_read_timeout` 유휴 타임아웃이었다. SSE 경로의 타임아웃을 완화하고 "
                    + "15초 코멘트 하트비트를 더해 유휴 상태에서도 바이트가 흐르게 했다. 서버 타임아웃만 늘리는 건 소용없었다.",
            ),
        ),
    )

    // 5 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "SSE 알림이 다른 노드 접속 사용자에게 안 간다 — 인메모리 emitter 의 한계",
            author = "정유나 · 플랫폼",
            summary = "스케일아웃 후 알림이 **같은 노드에 붙은 사용자에게만** 도달했다. emitter 를 로컬 메모리에 들고 있었던 탓. Redis Pub/Sub 팬아웃으로 노드 경계를 넘겼다.",
            tags = listOf("sse", "redis", "pub-sub", "scale-out"),
            exceptionClass = null,
            exceptionMessage = null,
            stackTrace = null,
            fingerprint = null,
            description = """
                2노드로 늘린 뒤 "알림이 가끔 안 온다" 는 제보. 재현해보니 알림 생성 요청을 처리한 노드와 사용자 SSE
                연결이 붙은 노드가 다르면 못 받았다. emitter 레지스트리가 각 노드의 `ConcurrentHashMap` 이라 서로를
                몰랐다.

                > 상태를 프로세스 메모리에 들면, 스케일아웃하는 순간 그 상태는 쪼개진다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "노드 어피니티로 증상 확정", WsOutcome.RESOLVED,
                    """
                    사용자를 노드 A 에 고정하고 노드 B 로 알림을 쏘면 미도달, 노드 A 로 쏘면 도달. emitter 가 노드-로컬임이
                    분명해졌다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "sticky session 으로 우회 — 막다른 길", WsOutcome.FAILED,
                    """
                    LB sticky session 으로 "사용자-노드" 를 고정해 당장은 가려졌다. 하지만 알림 *생성* 은 아무 노드에서나
                    일어나므로 생성 노드≠연결 노드 문제는 그대로. 게다가 배포 때 노드가 바뀌면 또 끊겼다. 근본 해결 아님.
                    """.trimIndent(),
                    durationMinutes = 45,
                ),
                WsStep(
                    "Redis Pub/Sub 팬아웃 설계", WsOutcome.RESOLVED,
                    """
                    알림을 userId 채널로 Redis 에 publish 하고, 모든 노드가 구독해 자기 노드에 그 user emitter 가 있으면
                    전달하는 구조로 바꿨다. 생성 노드가 어디든 전 노드에 방송된다.
                    """.trimIndent(),
                    durationMinutes = 70,
                    snippet = WsSnippet(
                        "노드 로컬 emitter + Redis 팬아웃", "kotlin",
                        "fun publish(userId: Long, e: NotiEvent) =\n    redis.convertAndSend(\"noti:\$userId\", e)   // 전 노드로 방송\n\nfun onMessage(userId: Long, e: NotiEvent) =\n    local[userId]?.send(e)                      // 내 노드에 있으면 전달",
                    ),
                ),
                WsStep(
                    "메시지 직렬화·역직렬화 정합성", WsOutcome.RESOLVED,
                    """
                    Pub/Sub 페이로드에 `LocalDateTime` 이 섞여 역직렬화가 깨졌다. Jackson `JavaTimeModule` 을
                    Redis 템플릿 직렬화기에도 등록해 통일.
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
                WsStep(
                    "구독 생명주기·중복 구독 방지", WsOutcome.RESOLVED,
                    """
                    노드당 채널 패턴 구독은 1회만 등록되도록 가드. emitter 종료 시 로컬 맵에서 제거해 유령 전달을 막음.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "교차 노드 도달 검증", WsOutcome.RESOLVED,
                    """
                    어느 노드에서 생성해도 어느 노드에 붙은 사용자든 100% 도달. sticky session 제거 후에도 안정.
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
            ),
            solution = WsSolution(
                "노드-로컬 emitter 앞에 Redis Pub/Sub 팬아웃을 둬 노드 경계를 넘긴다",
                "SSE emitter 가 프로세스 메모리에 있어 스케일아웃 시 상태가 쪼개진 게 원인. 알림을 userId 채널로 "
                    + "Redis 에 방송하고 전 노드가 구독해 자기 emitter 로 전달하자 생성 노드와 무관하게 도달했다. sticky session 은 근본 해결이 아니었다.",
            ),
        ),
    )

    // 6 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "추천 팔로우 조회 p95 7.4초 — cold-start 풀스캔 fallback",
            author = "최민석 · 백엔드",
            summary = "신규 유저의 추천 팔로우가 7초 넘게 걸렸다. 활동 데이터가 없을 때 **전 사용자 풀스캔** fallback 으로 빠진 탓. 인덱스 + 캐시 + 경계값 처리로 p95 를 120ms 로.",
            tags = listOf("performance", "n+1", "index", "cold-start"),
            exceptionClass = null,
            exceptionMessage = null,
            stackTrace = null,
            fingerprint = null,
            description = """
                가입 직후 홈의 "추천 팔로우" 위젯이 7.4초(p95)나 걸렸다. 기존 유저는 90ms. 차이는 "추천 근거가 될
                활동 데이터가 있느냐" 였다. 데이터가 없으면 인기 사용자 fallback 쿼리로 가는데, 그게 인덱스 없는
                풀스캔 + 정렬이었다.

                > 느린 건 평균이 아니라 특정 입력(cold-start)이다. p95 를 쪼개 보라.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "느린 요청만 분리 측정", WsOutcome.RESOLVED,
                    """
                    APM 트레이스를 유저 나이(가입 경과)별로 쪼개니 신규 유저만 느렸다. 느린 스팬은 단일 쿼리 —
                    fallback 경로의 인기 사용자 집계.
                    """.trimIndent(),
                    durationMinutes = 35,
                ),
                WsStep(
                    "애플리케이션 캐시만 추가 — 부분적, 막다른 길", WsOutcome.FAILED,
                    """
                    fallback 결과를 로컬 캐시했지만 cold 캐시(배포 직후·신규 키)에선 여전히 7초. 느린 쿼리 자체를
                    안 고치면 캐시 미스마다 사용자가 그 지연을 떠안았다.
                    """.trimIndent(),
                    durationMinutes = 40,
                ),
                WsStep(
                    "실행 계획으로 풀스캔 확인", WsOutcome.RESOLVED,
                    """
                    `EXPLAIN ANALYZE` 결과 `Seq Scan on follows` + 거대한 `Sort`. 집계 기준 컬럼에 인덱스가 없었다.
                    """.trimIndent(),
                    durationMinutes = 25,
                    snippet = WsSnippet(
                        "문제의 실행 계획", "text",
                        "Seq Scan on follows  (cost=0..98421 rows=1.2M)\n  Sort  (actual time=7110ms)  Sort Method: external merge  Disk: 240MB",
                    ),
                ),
                WsStep(
                    "커버링 인덱스 + 집계 사전계산", WsOutcome.RESOLVED,
                    """
                    `(followee_id)` 집계용 인덱스를 추가하고, 인기 사용자 top-N 은 10분 주기 배치로 사전 계산해 작은
                    테이블에 적재. fallback 은 그 테이블만 읽는다.
                    """.trimIndent(),
                    durationMinutes = 55,
                    snippet = WsSnippet(
                        "집계 인덱스", "sql",
                        "CREATE INDEX ix_follows_followee ON follows (followee_id);\n-- top-N 은 배치가 popular_users 로 사전 집계",
                    ),
                ),
                WsStep(
                    "캐시 워밍으로 cold-start 제거", WsOutcome.RESOLVED,
                    """
                    배포·스케줄로 인기 사용자 캐시를 미리 채워 첫 요청도 hit. 캐시 TTL 과 배치 주기를 맞춰 stale 최소화.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "신규 유저 p95 재측정", WsOutcome.RESOLVED,
                    """
                    신규 유저 추천 p95 7.4s → 120ms. 기존 유저 경로도 인덱스 덕에 90ms → 60ms 로 덤.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
            ),
            solution = WsSolution(
                "fallback 쿼리에 인덱스를 주고 top-N 을 배치로 사전계산 + 캐시 워밍한다",
                "cold-start fallback 이 인덱스 없는 풀스캔+외부정렬이라 신규 유저만 7초가 걸렸다. 집계 인덱스 + top-N "
                    + "사전계산 테이블 + 캐시 워밍으로 p95 를 120ms 로 낮췄다. 캐시만으로는 cold 미스를 못 막았다.",
            ),
        ),
    )

    // 7 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "Flyway 마이그레이션이 부팅 시 실행되지 않는다 — Boot 4.0 autoconfig 모듈",
            author = "한지우 · 플랫폼",
            summary = "Spring Boot 4.0 로 올린 뒤 Flyway 가 조용히 안 돌았다. `flyway-core` 만으론 부족하고 **전용 autoconfiguration 모듈**이 필요했던 것. 의존성 한 줄로 해결.",
            tags = listOf("flyway", "spring-boot-4", "migration", "autoconfigure"),
            exceptionClass = "org.springframework.jdbc.BadSqlGrammarException",
            exceptionMessage = "relation \"case_publishment\" does not exist",
            stackTrace = """
                org.springframework.jdbc.BadSqlGrammarException:
                PreparedStatementCallback; bad SQL grammar; relation "case_publishment" does not exist
                    at o.s.jdbc.support.SQLStateSQLExceptionTranslator.doTranslate
            """.trimIndent(),
            fingerprint = "9af0c3dd",
            description = """
                Boot 3.x 에선 멀쩡하던 Flyway 가 4.0 업그레이드 후 신규 환경에서 테이블을 안 만들었다. 애플리케이션은
                뜨는데 첫 쿼리에서 `relation does not exist`. 부팅 로그에 Flyway 배너가 아예 없었다 —
                마이그레이션 자체가 트리거되지 않은 것.

                > "에러가 아니라 침묵" 이면 autoconfiguration 이 조건을 못 맞춰 비활성화됐는지 본다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "부팅 로그에서 Flyway 부재 확인", WsOutcome.RESOLVED,
                    """
                    `Flyway Community Edition ... migrating schema` 로그가 전혀 없었다. 클래스패스엔 `flyway-core` 가
                    있는데 왜 autoconfig 가 안 걸리지?
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
                WsStep(
                    "baseline-on-migrate 만 켜기 — 막다른 길", WsOutcome.FAILED,
                    """
                    `spring.flyway.baseline-on-migrate=true` 같은 설정을 만졌지만 애초에 Flyway autoconfig 가 로드가
                    안 된 상태라 설정이 먹을 리 없었다. 증상이 아니라 "왜 비활성인가" 를 봐야 했다.
                    """.trimIndent(),
                    durationMinutes = 35,
                ),
                WsStep(
                    "ConditionEvaluationReport 로 비활성 원인 추적", WsOutcome.RESOLVED,
                    """
                    `--debug` 로 조건 평가 리포트를 보니 Flyway autoconfig 가 `did not match` — Boot 4.0 에서
                    autoconfiguration 이 별도 모듈(`spring-boot-flyway`)로 분리됐는데 그걸 안 넣었다.
                    """.trimIndent(),
                    durationMinutes = 40,
                    snippet = WsSnippet(
                        "조건 평가 리포트", "text",
                        "FlywayAutoConfiguration:\n  Did not match:\n    - @ConditionalOnClass ... FlywayAutoConfiguration (module not on classpath)",
                    ),
                ),
                WsStep(
                    "전용 모듈 의존성 추가", WsOutcome.RESOLVED,
                    """
                    `spring-boot-flyway` 모듈을 추가하자 autoconfig 가 매칭되고 부팅 시 마이그레이션이 돌았다. DB 쓰는
                    5개 서비스에 동일 적용.
                    """.trimIndent(),
                    durationMinutes = 20,
                    snippet = WsSnippet(
                        "Boot 4 Flyway 모듈", "kotlin",
                        "// Boot 4.0: flyway-core 만으론 autoconfig 미동작\nimplementation(\"org.springframework.boot:spring-boot-flyway\")\nimplementation(\"org.flywaydb:flyway-database-postgresql\")",
                        filePathOrClass = "build.gradle.kts",
                    ),
                ),
                WsStep(
                    "신규 환경 클린 부팅 검증", WsOutcome.RESOLVED,
                    """
                    빈 DB 에 띄우니 `V1 → V2` 순서로 마이그레이션 적용, `flyway_schema_history` 생성, 첫 쿼리 정상.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
                WsStep(
                    "CI 에 마이그레이션 검증 추가", WsOutcome.RESOLVED,
                    """
                    테스트컨테이너로 빈 DB 에 마이그레이션 전체를 적용하는 CI 스텝을 넣어 회귀를 자동 차단.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
            ),
            solution = WsSolution(
                "Boot 4.0 에선 Flyway autoconfiguration 전용 모듈(spring-boot-flyway)을 추가한다",
                "Boot 4.0 에서 Flyway autoconfig 가 별도 모듈로 분리돼, `flyway-core` 만으론 마이그레이션이 조용히 "
                    + "비활성화됐다. `spring-boot-flyway` 의존성을 추가하니 부팅 시 정상 실행됐다. 설정 튜닝은 원인이 아니었다.",
            ),
        ),
    )

    // 8 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "이미지 업로드 시 OCI Object Storage 403 — aws-chunked 미지원",
            author = "이서준 · 백엔드",
            summary = "로컬 MinIO 에선 되던 업로드가 OCI S3 호환 스토리지에선 403. AWS SDK v2 의 **aws-chunked 페이로드 서명**을 OCI 가 안 받아서였다. chunked encoding 을 끄자 해결.",
            tags = listOf("oci", "s3", "aws-sdk", "signature"),
            exceptionClass = "software.amazon.awssdk.services.s3.model.S3Exception",
            exceptionMessage = "The required information to complete authentication was not available. (Service: S3, Status Code: 403)",
            stackTrace = """
                software.amazon.awssdk.services.s3.model.S3Exception: The required information to complete
                authentication was not available. (Service: S3, Status Code: 403, Request ID: ...)
                    at software.amazon.awssdk.core.internal.http.CombinedResponseHandler.handleErrorResponse
            """.trimIndent(),
            fingerprint = "3b77e109",
            description = """
                아바타·첨부 업로드가 운영(OCI Object Storage, S3 호환)에서만 403. 자격증명·버킷 정책·시계는 다 정상.
                요청을 캡처해보니 본문이 `aws-chunked` 로 조각 서명돼 있었고, OCI 가 이 서명 방식을 지원하지 않아
                "인증 정보 부족" 으로 거절했다.

                > 로컬(MinIO)은 되고 특정 벤더만 403 이면, SDK 의 서명/전송 방식 호환성을 의심한다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "환경별 차이로 범위 좁히기", WsOutcome.RESOLVED,
                    """
                    동일 코드·동일 키가 MinIO 는 200, OCI 는 403. 벤더 차이가 변수. 자격증명 문제라면 둘 다 실패했을 것.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "키/정책/리전 점검 — 막다른 길", WsOutcome.FAILED,
                    """
                    access key, 버킷 정책, 리전, 엔드포인트를 몇 번이나 대조했지만 모두 정상. 403 메시지가 "자격증명
                    틀림" 이 아니라 "완료에 필요한 정보 부족" 이라는 미묘한 차이를 놓치고 있었다.
                    """.trimIndent(),
                    durationMinutes = 50,
                ),
                WsStep(
                    "요청 와이어 캡처로 aws-chunked 발견", WsOutcome.RESOLVED,
                    """
                    SDK 로그(`Content-Encoding: aws-chunked`, `x-amz-content-sha256: STREAMING-...`)를 보고 본문이
                    청크 단위로 서명돼 전송됨을 확인. OCI 는 이 스트리밍 서명을 미지원.
                    """.trimIndent(),
                    durationMinutes = 40,
                    snippet = WsSnippet(
                        "문제의 요청 헤더", "text",
                        "Content-Encoding: aws-chunked\nx-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                    ),
                ),
                WsStep(
                    "chunkedEncodingEnabled(false) 적용", WsOutcome.RESOLVED,
                    """
                    S3 클라이언트의 `serviceConfiguration` 에서 chunked encoding 을 끄니 본문이 단일 페이로드로 일반
                    서명돼 OCI 가 수락. iam 아바타 + core 첨부 양쪽 클라이언트에 동일 적용.
                    """.trimIndent(),
                    durationMinutes = 25,
                    snippet = WsSnippet(
                        "chunked encoding 비활성", "kotlin",
                        "S3Client.builder()\n    .serviceConfiguration(\n        S3Configuration.builder()\n            .chunkedEncodingEnabled(false)  // OCI 호환\n            .build())\n    .build()",
                    ),
                ),
                WsStep(
                    "대용량·멀티파트 업로드 확인", WsOutcome.RESOLVED,
                    """
                    작은 파일뿐 아니라 수십 MB 파일, presigned PUT 도 403 없이 성공. 서명 방식만 바뀌고 무결성은 유지.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "회귀 방지 통합테스트", WsOutcome.RESOLVED,
                    """
                    S3 호환 테스트컨테이너에 chunked on/off 를 토글하는 테스트를 추가해, 설정이 되돌아가면 실패하도록.
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
            ),
            solution = WsSolution(
                "OCI S3 호환 스토리지에는 S3Configuration.chunkedEncodingEnabled(false) 를 적용한다",
                "AWS SDK v2 가 본문을 `aws-chunked` 로 스트리밍 서명했는데 OCI 가 이를 미지원해 403 이 났다. "
                    + "`chunkedEncodingEnabled(false)` 로 단일 페이로드 서명으로 바꾸자 업로드가 통과했다. 자격증명·정책은 문제가 아니었다.",
            ),
        ),
    )

    // 9 ──────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "배포할 때마다 몇 초 순단 — Recreate 전략과 readiness 공백",
            author = "한지우 · 플랫폼",
            summary = "배포 때마다 수 초간 502 가 떴다. `Recreate` 전략이 **구 파드를 먼저 죽이고** 신 파드가 준비되길 기다린 탓. RollingUpdate + readiness probe + preStop 으로 무중단화.",
            tags = listOf("kubernetes", "zero-downtime", "readiness", "rolling-update"),
            exceptionClass = null,
            exceptionMessage = null,
            stackTrace = null,
            fingerprint = null,
            description = """
                배포 직후 5~8초간 `502 Bad Gateway` 가 번졌다. 사용자에겐 "방금 눌렀는데 에러" 로 보인다. 원인은
                두 가지가 겹쳤다 — 배포 전략이 `Recreate`(구 파드 종료 후 신 파드 기동)였고, readiness probe 가 없어
                부팅 중 파드로 트래픽이 들어갔다.

                > 무중단 배포는 "새 파드가 준비될 때까지 옛 파드를 살려두는" 겹침 구간이 핵심이다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "502 타이밍과 배포 상관 확인", WsOutcome.RESOLVED,
                    """
                    502 스파이크가 정확히 `kubectl rollout` 시점과 일치. 애플리케이션 에러율은 0 — LB 와 파드 사이의
                    공백이었다.
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
                WsStep(
                    "replica 수만 늘리기 — 막다른 길", WsOutcome.FAILED,
                    """
                    replica 를 2→4 로 늘렸지만 `Recreate` 전략은 전체를 동시에 죽였다 살리므로 순단 그대로. 전략을
                    안 바꾸면 수를 늘려도 의미 없었다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "RollingUpdate 로 전략 변경", WsOutcome.RESOLVED,
                    """
                    `maxUnavailable: 0`, `maxSurge: 1` 로 신 파드를 먼저 띄우고 준비되면 구 파드를 내리는 롤링으로 전환.
                    겹침 구간을 만들었다.
                    """.trimIndent(),
                    durationMinutes = 30,
                    snippet = WsSnippet(
                        "무중단 롤링 전략", "yaml",
                        "strategy:\n  type: RollingUpdate\n  rollingUpdate:\n    maxUnavailable: 0\n    maxSurge: 1",
                    ),
                ),
                WsStep(
                    "readiness probe 로 준비 전 트래픽 차단", WsOutcome.RESOLVED,
                    """
                    `/actuator/health/readiness` 를 readiness probe 로 걸어 DB·캐시 연결이 뜨기 전엔 Service 엔드포인트에
                    등록되지 않게 했다. 부팅 중 502 소멸.
                    """.trimIndent(),
                    durationMinutes = 35,
                    snippet = WsSnippet(
                        "readiness probe", "yaml",
                        "readinessProbe:\n  httpGet: { path: /actuator/health/readiness, port: 8080 }\n  periodSeconds: 3",
                    ),
                ),
                WsStep(
                    "preStop + graceful shutdown 으로 in-flight 보호", WsOutcome.RESOLVED,
                    """
                    종료 시 `preStop` 으로 몇 초 대기 후 SIGTERM, 서버는 graceful shutdown 으로 처리 중 요청을 마무리.
                    LB 디레지스터와 종료 사이 레이스를 없앴다.
                    """.trimIndent(),
                    durationMinutes = 30,
                ),
                WsStep(
                    "배포 중 연속 요청으로 검증", WsOutcome.RESOLVED,
                    """
                    배포하는 동안 1초 간격 헬스 요청을 쏴도 502 가 0. 롤아웃이 겹침 구간을 유지하며 진행됐다.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
            ),
            solution = WsSolution(
                "RollingUpdate(maxUnavailable 0) + readiness probe + preStop 으로 겹침 배포를 만든다",
                "`Recreate` 전략이 구 파드를 먼저 죽여 공백이 생겼고, readiness probe 부재로 준비 전 파드가 트래픽을 "
                    + "받았다. 롤링+readiness+preStop 으로 신·구 파드 겹침과 graceful 종료를 보장하자 순단이 사라졌다. replica 증설은 소용없었다.",
            ),
        ),
    )

    // 10 ─────────────────────────────────────────────────────────────────────
    add(
        WsCase(
            title = "클라이언트 4xx 가 전부 500 으로 매핑된다 — 예외 핸들러 우선순위",
            author = "박하늘 · 백엔드",
            summary = "검증 실패(400)·권한 없음(403)이 전부 500 으로 나갔다. 포괄 `Exception` 핸들러가 **구체 핸들러보다 먼저** 잡은 탓. 우선순위와 ProblemDetail 로 정리.",
            tags = listOf("exception-handling", "controller-advice", "http-status", "problemdetail"),
            exceptionClass = "org.springframework.web.bind.MethodArgumentNotValidException",
            exceptionMessage = "Validation failed for argument [0] ... default message [must not be blank]",
            stackTrace = """
                org.springframework.web.bind.MethodArgumentNotValidException: Validation failed for argument [0]
                    at o.s.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor.resolveArgument
                -- 그러나 응답은 500 Internal Server Error 로 나감 --
            """.trimIndent(),
            fingerprint = "c4d8f6b0",
            description = """
                프런트가 400 을 기대하는 검증 실패에 500 이 돌아와, 사용자에게 "일시적 오류" 토스트가 떴다. 서버 로그엔
                `MethodArgumentNotValidException` 이 분명히 찍히는데 상태코드만 500. `@ControllerAdvice` 의 포괄
                `handle(Exception)` 가 구체 핸들러를 가로챈 게 문제였다.

                > 같은 예외에 핸들러가 여럿이면, 더 구체적인 것이 이겨야 한다. 안 그러면 포괄 핸들러가 다 먹는다.
            """.trimIndent(),
            steps = listOf(
                WsStep(
                    "상태코드와 로그 예외 타입 불일치 포착", WsOutcome.RESOLVED,
                    """
                    로그의 예외는 검증 실패인데 응답은 500. 즉 예외는 맞게 던져지는데 *매핑* 이 틀렸다. 핸들러 쪽을 봤다.
                    """.trimIndent(),
                    durationMinutes = 25,
                ),
                WsStep(
                    "각 컨트롤러에서 try-catch — 막다른 길", WsOutcome.FAILED,
                    """
                    급한 김에 문제 엔드포인트마다 try-catch 로 400 을 직접 만들었다. 중복이 번지고 새 엔드포인트는 또
                    500. 전역 매핑을 고치지 않는 한 두더지잡기였다.
                    """.trimIndent(),
                    durationMinutes = 40,
                ),
                WsStep(
                    "핸들러 매칭 순서 분석", WsOutcome.RESOLVED,
                    """
                    한 `@ControllerAdvice` 안에 `handle(Exception)` 과 `handle(MethodArgumentNotValidException)` 이
                    공존했는데, advice 정렬·메서드 매칭 규칙상 포괄 핸들러가 먼저 걸려 구체 핸들러가 도달 못 했다.
                    """.trimIndent(),
                    durationMinutes = 35,
                    snippet = WsSnippet(
                        "포괄 핸들러가 가로채던 구조", "kotlin",
                        "@ExceptionHandler(Exception::class)   // 이게 먼저 매칭되면\nfun handleAll(e: Exception) = status(500)\n\n@ExceptionHandler(MethodArgumentNotValidException::class)  // 도달 못 함\nfun handleValidation(e: ...) = status(400)",
                    ),
                ),
                WsStep(
                    "구체 핸들러 분리 + ProblemDetail 표준화", WsOutcome.RESOLVED,
                    """
                    포괄 `Exception` 핸들러는 마지막 폴백으로만 두고, 검증/권한/404 를 구체 핸들러로 분리. 응답은 전부
                    RFC 7807 `ProblemDetail`(type·title·status·traceId)로 통일.
                    """.trimIndent(),
                    durationMinutes = 50,
                    snippet = WsSnippet(
                        "구체 핸들러 + ProblemDetail", "kotlin",
                        "@ExceptionHandler(MethodArgumentNotValidException::class)\nfun handleValidation(e: ...): ProblemDetail =\n    ProblemDetail.forStatusAndDetail(BAD_REQUEST, e.fieldErrorsSummary())",
                    ),
                ),
                WsStep(
                    "전 상태코드 계약 테스트", WsOutcome.RESOLVED,
                    """
                    400/403/404/409/500 각각을 유발하는 MockMvc 테스트를 추가해 상태코드-예외 매핑을 계약으로 고정.
                    """.trimIndent(),
                    durationMinutes = 40,
                ),
                WsStep(
                    "프런트 에러 UX 회복 확인", WsOutcome.RESOLVED,
                    """
                    검증 실패가 400 + 필드별 메시지로 내려가 폼 인라인 에러가 정상 복구. "일시적 오류" 오탐 토스트 소멸.
                    """.trimIndent(),
                    durationMinutes = 20,
                ),
            ),
            solution = WsSolution(
                "포괄 Exception 핸들러는 최후 폴백으로만 두고, 구체 예외를 분리해 ProblemDetail 로 매핑한다",
                "포괄 `handle(Exception)` 가 구체 핸들러보다 먼저 매칭돼 4xx 가 전부 500 이 됐다. 검증·권한·404 를 "
                    + "구체 핸들러로 분리하고 RFC 7807 ProblemDetail 로 통일하자 상태코드가 정확해졌다. 엔드포인트별 try-catch 는 두더지잡기였다.",
            ),
        ),
    )
}
