package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.shared.util.FingerprintGenerator
import java.time.Instant
import java.time.LocalDateTime

/**
 * 데모 워크스페이스 시드 — **데모 봇 소유**의 "실제 같은" 에러 케이스.
 * 각 케이스가 스택트레이스(스냅샷) + 재현/조사 스텝 + (해결건) 솔루션 + 첨부(스크린샷 SVG·JSON·로그)를
 * 갖춰, 둘러보기 방문자가 제품 사용감을 바로 체감하도록 한다.
 *
 * - 가시성 PUBLIC: Search 탭(`/error-cases/search`, 전역 PUBLIC)에서 누구나 조회 가능.
 * - 워크스페이스는 데모 WS 로 묶여 reset 이 ws 단위 wipe+reseed(스텝/솔루션/첨부는 purge 가 cascade).
 * - 첨부는 S3 put 이 필요 — 스토리지 미가용(테스트)이면 `demo.seed-attachments=false` 로 끈다.
 */
@Service
class DemoSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val storagePort: AttachmentStoragePort,
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    /** 시드 세트 크기 — 부트스트랩이 "시드가 바뀌었는지"(재시드 필요) 판정에 사용. */
    val expectedCount: Int get() = SEED.size

    @Transactional
    fun ensureSeeded(workspaceId: Long, botUserId: Long): Int {
        val existing = errorCaseRepository.search(criteria(workspaceId, limit = 1))
        if (existing.isNotEmpty()) return 0
        return seedFresh(workspaceId, botUserId)
    }

    @Transactional
    fun seedFresh(workspaceId: Long, botUserId: Long): Int {
        SEED.forEach { s -> seedOne(s, workspaceId, botUserId) }
        return SEED.size
    }

    private fun seedOne(s: RichSeed, workspaceId: Long, botUserId: Long) {
        val attachments = if (props.seedAttachments) s.attachments.mapNotNull { buildAttachment(it, botUserId) } else emptyList()

        val snapshot = buildSnapshot(s)
        val case = ErrorCase.create(
            ownerUserId = botUserId,
            title = s.title,
            project = s.project,
            snapshot = snapshot,
            description = s.description,
            meta = Meta.create(workspaceId = workspaceId, severityCode = s.severityCode),
            visibility = Visibility.PUBLIC,
            snippets = emptyList(),
            attachments = attachments,
            tags = s.tags,
            occurredAt = LocalDateTime.now().minusDays(s.daysAgo).minusHours(s.hoursAgo),
        )
        applyStatus(case, s.status, botUserId)

        val caseId = errorCaseRepository.save(case).id!!

        val stepIds = s.steps.mapIndexed { i, st ->
            stepRepository.save(
                Step.create(
                    errorCaseId = caseId,
                    authorUserId = botUserId,
                    orderIndex = i,
                    title = st.title,
                    status = st.status,
                    attemptType = st.attemptType,
                    body = st.body,
                    insight = st.insight,
                ),
            ).id!!
        }
        if (s.solutionTitle != null && stepIds.isNotEmpty()) {
            solutionRepository.save(
                Solution.create(errorCaseId = caseId, authorUserId = botUserId, title = s.solutionTitle, stepIds = stepIds),
            )
        }
    }

    private fun applyStatus(case: ErrorCase, target: ErrorCaseStatus, actor: Long) {
        when (target) {
            ErrorCaseStatus.OPEN, ErrorCaseStatus.DRAFT -> {}
            ErrorCaseStatus.IN_PROGRESS -> case.transitionTo(ErrorCaseStatus.IN_PROGRESS, actor)
            ErrorCaseStatus.RESOLVED -> {
                case.transitionTo(ErrorCaseStatus.IN_PROGRESS, actor)
                case.transitionTo(ErrorCaseStatus.RESOLVED, actor)
            }
            ErrorCaseStatus.CLOSED -> case.transitionTo(ErrorCaseStatus.CLOSED, actor)
        }
    }

    private fun buildSnapshot(s: RichSeed): ErrorSnapshot {
        val raw = RawStackTrace(s.stackTrace.trim())
        val fp = FingerprintGenerator.generate(exceptionClass = s.exceptionClass, rawStackTrace = raw)
        return ErrorSnapshot(
            rawPaste = s.stackTrace.trim(),
            exceptionClass = s.exceptionClass,
            exceptionMessage = s.exceptionMessage,
            rawStackTrace = raw,
            fingeprint = fp,
        )
    }

    /** 첨부 1건: S3 put → row 저장. 스토리지 실패 시 null(해당 첨부만 건너뜀). */
    private fun buildAttachment(a: SeedAtt, botUserId: Long): Attachment? {
        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = "attachments/${markerId.take(2).ifBlank { "00" }}/$markerId"
        val bytes = a.content.toByteArray(Charsets.UTF_8)
        return runCatching {
            storagePort.put(objectKey, bytes, a.contentType)
            attachmentRepository.save(
                Attachment(
                    markerId = markerId,
                    title = a.title,
                    caption = a.caption,
                    fileName = a.fileName,
                    contentType = a.contentType,
                    size = bytes.size.toLong(),
                    kind = a.kind,
                    objectKey = objectKey,
                    uploadedByUserId = botUserId,
                    uploadedAt = Instant.now(),
                ),
            )
        }.getOrElse {
            log.warn { "demo seed attachment skipped (${a.fileName}): ${it.message}" }
            null
        }
    }

    private fun criteria(workspaceId: Long, limit: Int) = ErrorCaseSearchCriteria(
        workspaceId = workspaceId, ownerUserId = null, status = null, fingerprint = null,
        visibility = null, cursorCreatedAt = null, cursorId = null, limit = limit,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 시드 정의
    // ─────────────────────────────────────────────────────────────────────────

    private enum class Att { CHART, TERMINAL, JSON, LOG }

    private class SeedAtt(type: Att, val fileName: String, val title: String, val caption: String?, val content: String) {
        val contentType = when (type) { Att.CHART, Att.TERMINAL -> "image/svg+xml"; Att.JSON -> "application/json"; Att.LOG -> "text/plain" }
        val kind = when (type) { Att.CHART, Att.TERMINAL -> AttachmentKind.IMAGE; Att.JSON -> AttachmentKind.JSON; Att.LOG -> AttachmentKind.TEXT }
    }

    private class SeedStep(
        val title: String,
        val status: StepStatus,
        val attemptType: String,
        val body: String,
        val insight: String? = null,
    )

    private class RichSeed(
        val title: String,
        val project: String,
        val status: ErrorCaseStatus,
        val severityCode: Int?,
        val daysAgo: Long,
        val hoursAgo: Long,
        val tags: List<String>,
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val stackTrace: String,
        val description: String,
        val steps: List<SeedStep>,
        val solutionTitle: String?,
        val attachments: List<SeedAtt>,
    )

    companion object {
        // ── 첨부 콘텐츠 생성기 (awt 없이 SVG/텍스트) ───────────────────────────
        private fun svgChart(title: String, unit: String, values: List<Int>, accent: String = "#ef4444"): String {
            val w = 720; val h = 360; val padL = 56; val padB = 44; val padT = 56; val padR = 24
            val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(1)
            val plotW = w - padL - padR; val plotH = h - padT - padB
            val stepX = if (values.size > 1) plotW.toDouble() / (values.size - 1) else 0.0
            val pts = values.mapIndexed { i, v ->
                val x = padL + stepX * i
                val y = padT + plotH - (v.toDouble() / maxV) * plotH
                "%.1f,%.1f".format(x, y)
            }.joinToString(" ")
            val last = values.lastIndex
            val lastX = padL + stepX * last
            val lastY = padT + plotH - (values.last().toDouble() / maxV) * plotH
            val grid = (0..4).joinToString("") { g ->
                val y = padT + plotH - plotH * g / 4
                val label = maxV * g / 4
                """<line x1="$padL" y1="$y" x2="${w - padR}" y2="$y" stroke="#e5e7eb" stroke-width="1"/>""" +
                    """<text x="${padL - 8}" y="${y + 4}" font-size="11" fill="#9ca3af" text-anchor="end">$label</text>"""
            }
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" font-family="ui-sans-serif,system-ui,sans-serif">
<rect width="$w" height="$h" rx="14" fill="#ffffff" stroke="#e5e7eb"/>
<text x="$padL" y="30" font-size="16" font-weight="700" fill="#111827">$title</text>
<text x="${w - padR}" y="30" font-size="12" fill="#9ca3af" text-anchor="end">$unit</text>
$grid
<polyline points="$pts" fill="none" stroke="$accent" stroke-width="2.5" stroke-linejoin="round"/>
<circle cx="%.1f" cy="%.1f" r="4.5" fill="$accent"/>
</svg>""".format(lastX, lastY)
        }

        private fun svgTerminal(title: String, lines: List<String>): String {
            val w = 760; val lineH = 20; val top = 54
            val h = top + lines.size * lineH + 18
            val body = lines.mapIndexed { i, raw ->
                val y = top + i * lineH
                val color = when {
                    raw.contains("ERROR") || raw.contains("Exception") || raw.startsWith("  at ") && i == 1 -> "#f87171"
                    raw.startsWith("  at ") || raw.startsWith("\tat ") -> "#9ca3af"
                    raw.contains("WARN") -> "#fbbf24"
                    else -> "#d1d5db"
                }
                val safe = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                """<text x="18" y="$y" font-size="12.5" font-family="ui-monospace,Menlo,monospace" fill="$color">$safe</text>"""
            }.joinToString("\n")
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h">
<rect width="$w" height="$h" rx="12" fill="#0b0f17"/>
<rect width="$w" height="34" rx="12" fill="#161b26"/>
<circle cx="20" cy="17" r="5" fill="#ef4444"/><circle cx="40" cy="17" r="5" fill="#f59e0b"/><circle cx="60" cy="17" r="5" fill="#22c55e"/>
<text x="$w" y="21" font-size="12" fill="#6b7280" text-anchor="end" font-family="ui-monospace,monospace" dx="-14">$title</text>
$body
</svg>"""
        }

        private val SEED: List<RichSeed> = buildList {
            add(RichSeed(
                title = "로그인 직후 홈 진입에서 간헐적 NullPointerException",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 1, hoursAgo = 3,
                tags = listOf("kotlin", "npe", "oauth", "dashboard"),
                exceptionClass = "java.lang.NullPointerException",
                exceptionMessage = "Cannot invoke \"String.length()\" because the return value of \"IamPublicProfile.avatarUrl()\" is null",
                stackTrace = """
java.lang.NullPointerException: Cannot invoke "String.length()" because "avatarUrl" is null
    at org.studieojavry.coreapi.home.ProfileHydrator.hydrate(ProfileHydrator.kt:48)
    at org.studieojavry.coreapi.home.HomeDashboardService.recentActive(HomeDashboardService.kt:72)
    at org.studieojavry.coreapi.home.HomeController.recentActiveCases(HomeController.kt:39)
    at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
""",
                description = "GitHub OAuth 로그인 직후 홈 대시보드 진입에서 간헐적 500(NPE). 아바타가 없는(신규) 계정에서만 재현. 프로필 하이드레이션이 avatarUrl 을 non-null 로 가정했다.",
                steps = listOf(
                    SeedStep("신규 계정으로 재현", StepStatus.RESOLVED, "INVESTIGATION",
                        "아바타 미설정 계정 3개로 로그인 → 홈 진입 시 100% 재현. 기존 계정은 재현 안 됨 → avatarUrl null 분기 의심."),
                    SeedStep("ProfileHydrator 널 가드 추가", StepStatus.RESOLVED, "CODE_CHANGE",
                        "avatarUrl 을 String? 로 받아 null 이면 기본 아바타 URL 로 대체.",
                        insight = "외부(iam) 응답 필드는 전부 nullable 로 받고 경계에서 기본값 처리하는 게 안전하다."),
                    SeedStep("회귀 테스트 추가", StepStatus.RESOLVED, "CODE_CHANGE",
                        "avatarUrl=null 픽스처로 하이드레이션 단위 테스트 추가 → green."),
                ),
                solutionTitle = "avatarUrl null 가드 + 기본 아바타 폴백",
                attachments = listOf(
                    SeedAtt(Att.TERMINAL, "npe-stacktrace.svg", "에러 스택트레이스", "홈 진입 시 NPE",
                        svgTerminal("core-api · stdout", listOf(
                            "2026-10-16 14:02:11 ERROR HomeController - 500 on /users/me/recent-active-cases",
                            "java.lang.NullPointerException: avatarUrl is null",
                            "  at ProfileHydrator.hydrate(ProfileHydrator.kt:48)",
                            "  at HomeDashboardService.recentActive(HomeDashboardService.kt:72)",
                            "  at HomeController.recentActiveCases(HomeController.kt:39)"))),
                    SeedAtt(Att.JSON, "iam-profile-response.json", "iam 프로필 응답", "avatarUrl 이 null 인 신규 계정",
                        """{
  "userId": 10432,
  "handle": "newbie-dev",
  "displayName": "Newbie Dev",
  "avatarUrl": null,
  "bio": null
}"""),
                ),
            ))
            add(RichSeed(
                title = "PDF 공개 export 중 OutOfMemoryError (Java heap space)",
                project = "publish-api", status = ErrorCaseStatus.IN_PROGRESS, severityCode = 1, daysAgo = 0, hoursAgo = 5,
                tags = listOf("jvm", "oom", "pdf", "memory"),
                exceptionClass = "java.lang.OutOfMemoryError",
                exceptionMessage = "Java heap space",
                stackTrace = """
java.lang.OutOfMemoryError: Java heap space
    at com.openhtmltopdf.render.displaylist.DisplayListPainter.paint(DisplayListPainter.java:81)
    at com.openhtmltopdf.pdfboxout.PdfBoxRenderer.renderPage(PdfBoxRenderer.java:612)
    at org.studieojavry.publishapi.export.PdfExporter.export(PdfExporter.kt:57)
    at org.studieojavry.publishapi.publishment.PublicArticleController.exportPdf(PublicArticleController.kt:88)
""",
                description = "이미지가 많은 공개 아티클을 PDF 로 export 할 때 1 vCPU/512MB 컨테이너가 힙을 소진해 OOM. 동시 요청 2건이면 더 빨리 터진다. 렌더 큐 직렬화 + 이미지 다운스케일 검토 중.",
                steps = listOf(
                    SeedStep("힙 덤프로 점유 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "-XX:+HeapDumpOnOutOfMemoryError 로 덤프 수집. BufferedImage 가 힙의 70% 차지 — 원본 해상도 이미지를 통째로 들고 렌더."),
                    SeedStep("렌더 동시성 1로 제한", StepStatus.IN_PROGRESS, "CONFIG",
                        "export 전용 세마포어(permit=1)로 PDF 렌더 직렬화. 피크 OOM 빈도 급감했으나 대형 문서 1건은 여전히 아슬아슬."),
                    SeedStep("업로드 시 이미지 다운스케일", StepStatus.IN_PROGRESS, "CODE_CHANGE",
                        "첨부 저장 시 장변 2000px 로 리사이즈한 렌더용 사본 생성 검토."),
                ),
                solutionTitle = null,
                attachments = listOf(
                    SeedAtt(Att.CHART, "heap-usage.svg", "힙 사용량", "export 구간에서 힙 급상승 → OOM",
                        svgChart("JVM Heap Used (export 구간)", "MB", listOf(180, 210, 240, 300, 380, 470, 512, 512), "#ef4444")),
                ),
            ))
            add(RichSeed(
                title = "피크 시간마다 터지는 503 — HikariCP 커넥션 풀 고갈",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 1, daysAgo = 4, hoursAgo = 2,
                tags = listOf("hikaricp", "connection-pool", "timeout", "jpa"),
                exceptionClass = "org.springframework.dao.DataAccessResourceFailureException",
                exceptionMessage = "Unable to acquire JDBC Connection; HikariPool-1 - Connection is not available, request timed out after 30000ms",
                stackTrace = """
org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
    at org.springframework.orm.jpa.vendor.HibernateJpaDialect.convert(HibernateJpaDialect.java:275)
Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
""",
                description = "트래픽 피크 때 /error-cases 조회가 503 로 무더기 실패. HikariCP 풀(10)이 고갈되고 30초 타임아웃. open-in-view 로 뷰 렌더까지 커넥션을 쥐고 있던 게 원인.",
                steps = listOf(
                    SeedStep("풀 메트릭 관찰", StepStatus.RESOLVED, "INVESTIGATION",
                        "hikaricp_connections_active 가 피크에 10/10 으로 붙고 pending 이 쌓임. 평균 쿼리는 빠른데 커넥션 보유 시간이 길다."),
                    SeedStep("open-in-view 비활성", StepStatus.RESOLVED, "CONFIG",
                        "spring.jpa.open-in-view=false 로 뷰 렌더 구간의 커넥션 점유 제거.",
                        insight = "OSIV 는 지연로딩 편의 대신 커넥션을 요청 끝까지 잡아 풀 고갈의 흔한 원인."),
                    SeedStep("N+1 제거 + 풀 재튜닝", StepStatus.RESOLVED, "CODE_CHANGE",
                        "작성자 조회 N+1 을 batch fetch 로 바꾸고 풀 크기를 코어 수 기준으로 재설정."),
                ),
                solutionTitle = "OSIV off + N+1 제거로 커넥션 보유시간 단축",
                attachments = listOf(
                    SeedAtt(Att.CHART, "pool-active.svg", "활성 커넥션", "피크에 10/10 고갈",
                        svgChart("HikariCP active connections", "conns", listOf(2, 3, 5, 8, 10, 10, 10, 4), "#f59e0b")),
                    SeedAtt(Att.LOG, "hikari-timeout.log", "타임아웃 로그", null,
                        """2026-10-13 20:11:03.412 WARN  HikariPool-1 - Connection is not available, request timed out after 30000ms
2026-10-13 20:11:03.980 ERROR ErrorCaseController - 503 GET /api/v1/error-cases (ws=public)
2026-10-13 20:11:04.117 WARN  HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=31s)
2026-10-13 20:11:33.420 WARN  HikariPool-1 - Connection is not available, request timed out after 30000ms"""),
                ),
            ))
            add(RichSeed(
                title = "알림 SSE 연결이 약 30초마다 끊김",
                project = "noti-api", status = ErrorCaseStatus.RESOLVED, severityCode = 3, daysAgo = 2, hoursAgo = 6,
                tags = listOf("sse", "timeout", "realtime", "nginx"),
                exceptionClass = "java.io.IOException",
                exceptionMessage = "Broken pipe",
                stackTrace = """
java.io.IOException: Broken pipe
    at java.base/sun.nio.ch.SocketDispatcher.write0(Native Method)
    at org.apache.catalina.connector.OutputBuffer.realWriteBytes(OutputBuffer.java:342)
    at org.studieojavry.notiapi.sse.NotificationSseBroker.deliverToLocal(NotificationSseBroker.kt:64)
""",
                description = "EventSource 가 약 30초 간격으로 재연결 반복. 프록시 idle timeout(30s)과 서버 heartbeat 주기 불일치. keep-alive 코멘트 프레임을 timeout 보다 짧게 보내 해결.",
                steps = listOf(
                    SeedStep("재연결 주기 측정", StepStatus.RESOLVED, "INVESTIGATION",
                        "브라우저 네트워크 탭에서 SSE 가 정확히 ~30s 마다 재연결. 서버는 heartbeat 를 45s 로 보내고 있었음."),
                    SeedStep("heartbeat 15s 로 단축", StepStatus.RESOLVED, "CONFIG",
                        "주석 프레임(`:ping`)을 15s 간격으로 전송 → 프록시 idle timeout 전에 트래픽 발생 → 연결 유지."),
                ),
                solutionTitle = "heartbeat 주기를 프록시 timeout 아래로 조정",
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "쿠폰 발급 동시성 — 멀티 인스턴스에서 한도 초과 발급",
                project = "core-api", status = ErrorCaseStatus.IN_PROGRESS, severityCode = 1, daysAgo = 8, hoursAgo = 1,
                tags = listOf("concurrency", "redis", "redisson", "lock"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 재현 로그 (부하 테스트, 인스턴스 2대)
coupon_id=SUMMER2026 limit=1000
instance-a issued=623
instance-b issued=502
total issued=1125  (EXPECTED <= 1000)  ← 125 초과 발급
root-cause: check-then-update 사이 race, 공유 락 없음
""",
                description = "한정 쿠폰을 2개 인스턴스가 공유 DB에서 발급할 때 한도(1000)를 넘겨 1125건 발급. 잔여수량 확인과 차감 사이 race. Redisson 분산 락 + Redis 장애 시 조건부 UPDATE fallback 로 이중화 중.",
                steps = listOf(
                    SeedStep("2-인스턴스 부하로 초과 재현", StepStatus.RESOLVED, "INVESTIGATION",
                        "k6 로 2개 인스턴스에 동시 발급 → 한도 초과 재현(1125/1000). 단일 인스턴스에선 재현 안 됨 → 분산 race 확정."),
                    SeedStep("Redisson 분산 락 도입", StepStatus.IN_PROGRESS, "DEPENDENCY",
                        "쿠폰코드 키로 분산 락 → 임계구역 직렬화. 초과 사라졌으나 Redis 단일 장애점 우려."),
                    SeedStep("Redis 장애 fallback 설계", StepStatus.IN_PROGRESS, "CODE_CHANGE",
                        "Redis 불가 시 `UPDATE ... SET issued=issued+1 WHERE issued<limit` 조건부 UPDATE + UNIQUE 제약으로 DB 레벨 보증.",
                        insight = "분산 락은 성능 경로, 정확성의 최종 보루는 DB 제약에 둔다(락과 제약의 이중화)."),
                ),
                solutionTitle = null,
                attachments = listOf(
                    SeedAtt(Att.CHART, "coupon-overissue.svg", "발급량", "한도 1000 초과(1125)",
                        svgChart("쿠폰 발급 누적 (2 인스턴스)", "issued", listOf(120, 320, 560, 780, 940, 1040, 1125), "#ef4444")),
                    SeedAtt(Att.JSON, "loadtest-summary.json", "부하 테스트 요약", null,
                        """{
  "scenario": "coupon-issue-2instances",
  "vus": 100,
  "duration": "60s",
  "limit": 1000,
  "issued_total": 1125,
  "over_issued": 125,
  "instances": { "a": 623, "b": 502 }
}"""),
                ),
            ))
            add(RichSeed(
                title = "SSE 알림이 다른 노드 접속 사용자에게 도달하지 않음",
                project = "noti-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 3, hoursAgo = 4,
                tags = listOf("sse", "redis", "pubsub", "scaling"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 관찰: 노드 2대, 사용자별 SseEmitter 는 접속한 노드에만 등록
A-node: createNotification(userId=42) -> local emitters: []   (user 42 는 B-node 접속)
B-node: emitters[42] = 1  but never receives (이벤트가 A-node 로컬에만 존재)
=> 교차 노드 전달 경로 없음
""",
                description = "알림을 생성한 노드와 사용자가 접속한 노드가 다르면 SSE 가 전달되지 않음. SseEmitter 레지스트리가 노드-로컬이라 발생. Redis pub/sub 채널로 모든 노드가 구독해 fan-out 하도록 변경.",
                steps = listOf(
                    SeedStep("교차 노드 미전달 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "A노드에서 생성한 이벤트가 B노드 접속 사용자에게 안 감. PUBSUB NUMSUB 로 구독자 0 확인."),
                    SeedStep("Redis 채널 fan-out 도입", StepStatus.RESOLVED, "CODE_CHANGE",
                        "알림 생성 시 `noti:realtime` 채널에 publish, 전 노드가 구독 → 자기 로컬 emitter 로 전달.",
                        insight = "노드-로컬 상태(emitter)는 유지하되, 트리거만 pub/sub 로 브로드캐스트하면 수평확장에서 깨지지 않는다."),
                    SeedStep("라운드트립 검증", StepStatus.RESOLVED, "CODE_CHANGE",
                        "publish→구독→로컬 전달 바이트 동일성 통합테스트 추가, PUBSUB NUMSUB==노드 수 확인."),
                ),
                solutionTitle = "Redis pub/sub 로 노드 간 SSE fan-out",
                attachments = listOf(
                    SeedAtt(Att.TERMINAL, "pubsub-check.svg", "구독자 확인", "fan-out 전/후",
                        svgTerminal("redis-cli", listOf(
                            "> PUBSUB NUMSUB noti:realtime", "1) \"noti:realtime\"", "2) (integer) 2",
                            "# 2 = 양 노드 구독 → 교차 전달 OK"))),
                ),
            ))
            add(RichSeed(
                title = "로그인 브라우저 요청이 403 Invalid CORS request",
                project = "insight-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 6, hoursAgo = 2,
                tags = listOf("cors", "security", "gateway"),
                exceptionClass = null, exceptionMessage = "Invalid CORS request",
                stackTrace = """
2026-10-11 09:12:44 WARN  o.s.w.c.HttpSessionSecurityContextRepository - 403
HTTP 403 Forbidden
X-Trace-Id: 9b2a...
body: {"title":"Forbidden","detail":"Invalid CORS request","status":403}
origin: https://app.error-archive.com
""",
                description = "로그인 후 특정 API 호출이 403 Invalid CORS. 다운스트림(insight)이 prod 경로에 CORS 를 와일드카드로 중복 등록 → 게이트웨이 CORS 헤더와 충돌. CORS 는 게이트웨이가 소유하도록 정리.",
                steps = listOf(
                    SeedStep("중복 Allow-Origin 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "응답에 Access-Control-Allow-Origin 이 2번 — 게이트웨이 + insight. 브라우저가 중복 헤더로 차단."),
                    SeedStep("다운스트림 CORS 제거", StepStatus.RESOLVED, "CODE_CHANGE",
                        "insight 의 prod CORS 등록 삭제, local 프로파일 전용으로만 유지.",
                        insight = "엣지(게이트웨이)가 CORS 를 단독 소유해야 헤더 중복/정책 불일치가 안 생긴다."),
                ),
                solutionTitle = "CORS 소유권을 게이트웨이로 일원화",
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "추천 팔로우 조회 p95 7.4초 — cold-start full-scan fallback",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 12, hoursAgo = 2,
                tags = listOf("performance", "query", "n+1", "index"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# APM 트레이스 (suggested-followees)
GET /api/v1/users/me/suggested-followees  p95=7,450ms
  └ fallback: findRandomPublicCaseOwners  6,900ms  (full table scan, no index)
  └ author hydration (iam)  N calls (N=후보 수)  520ms
""",
                description = "추천 팔로우가 cold-start(팔로잉 없음)에서 전체 스캔 fallback + 작성자 N+1 로 p95 7.4초. fan-out 병렬화 + fallback 쿼리 인덱스/상한으로 89ms 로 단축.",
                steps = listOf(
                    SeedStep("트레이스로 병목 분해", StepStatus.RESOLVED, "INVESTIGATION",
                        "fallback full-scan 6.9s + 작성자 하이드레이션 N+1 이 지배적."),
                    SeedStep("fallback 인덱스 + 상한", StepStatus.RESOLVED, "CODE_CHANGE",
                        "랜덤 공개 작성자 조회에 커버링 인덱스 + LIMIT, 전체 스캔 제거."),
                    SeedStep("작성자 하이드레이션 batch", StepStatus.RESOLVED, "CODE_CHANGE",
                        "iam 프로필 조회를 건별 → 1회 batch 로 병합(N+1 제거).",
                        insight = "cold-start 경로일수록 '느린 fallback + N+1' 이 겹쳐 최악 p95 를 만든다 — 둘 다 같이 잡아야 효과."),
                ),
                solutionTitle = "fallback 인덱스화 + 작성자 조회 batch 로 p95 7,450→89ms",
                attachments = listOf(
                    SeedAtt(Att.CHART, "p95-before-after.svg", "p95 개선", "7,450ms → 89ms",
                        svgChart("suggested-followees p95", "ms", listOf(7450, 5200, 2600, 900, 300, 120, 89), "#10b981")),
                ),
            ))
            add(RichSeed(
                title = "Flyway 마이그레이션이 부팅 시 실행되지 않음",
                project = "iam-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 16, hoursAgo = 4,
                tags = listOf("flyway", "spring-boot-4", "db", "migration"),
                exceptionClass = "org.hibernate.tool.schema.spi.SchemaManagementException",
                exceptionMessage = "Schema-validation: missing table [iam_refresh_token]",
                stackTrace = """
org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing table [iam_refresh_token]
    at org.hibernate.tool.schema.internal.AbstractSchemaValidator.validateTable(AbstractSchemaValidator.java:132)
    at org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaConfiguration...
# ddl-auto=validate 인데 Flyway 가 migrate 를 돌리지 않아 테이블이 없음
""",
                description = "Spring Boot 4.0 에서 flyway-core 만으론 부팅 시 자동 migrate 가 안 돌아, ddl-auto=validate 가 없는 테이블에서 실패. Boot4 는 autoconfig 가 모듈 분리됨 → spring-boot-flyway 모듈을 함께 추가해 해결.",
                steps = listOf(
                    SeedStep("migrate 미실행 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "flyway_schema_history 테이블 자체가 없음 → FlywayAutoConfiguration 미적용 확인."),
                    SeedStep("spring-boot-flyway 모듈 추가", StepStatus.RESOLVED, "DEPENDENCY",
                        "flyway-core 외에 org.springframework.boot:spring-boot-flyway 추가 → 부팅 시 migrate 동작.",
                        insight = "Boot 4.0 은 autoconfig 를 모듈로 쪼갰다 — 라이브러리만 넣으면 '왜 자동설정이 안 돌지' 함정에 빠진다."),
                ),
                solutionTitle = "spring-boot-flyway autoconfig 모듈 추가",
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "이미지 업로드 시 OCI Object Storage 403 (aws-chunked)",
                project = "core-api", status = ErrorCaseStatus.CLOSED, severityCode = 3, daysAgo = 20, hoursAgo = 0,
                tags = listOf("s3", "oci", "upload", "sdk"),
                exceptionClass = "software.amazon.awssdk.services.s3.model.S3Exception",
                exceptionMessage = "There is a required information to complete authentication that was not provided (403)",
                stackTrace = """
software.amazon.awssdk.services.s3.model.S3Exception: ... (403)
    at software.amazon.awssdk.core.internal.http.CombinedResponseHandler.handleErrorResponse
    at org.studieojavry.coreapi.errorcase.attachment.infrastructure.s3.S3AttachmentStorageAdapter.put(S3AttachmentStorageAdapter.kt:41)
# AWS SDK v2 가 aws-chunked content-encoding 으로 전송 → OCI 미지원 → 403
""",
                description = "첨부 업로드 putObject 가 OCI 에서 403. AWS SDK v2 가 기본으로 aws-chunked 스트리밍 서명을 쓰는데 OCI S3 호환 API 가 미지원. S3Configuration 에 chunkedEncodingEnabled(false) 로 해결.",
                steps = listOf(
                    SeedStep("요청 서명 방식 비교", StepStatus.RESOLVED, "INVESTIGATION",
                        "MinIO(local)에선 성공, OCI(prod)에서만 403 → 공급자별 호환성 이슈로 좁힘. 요청 헤더에 aws-chunked 확인."),
                    SeedStep("chunkedEncodingEnabled(false)", StepStatus.RESOLVED, "CONFIG",
                        "S3Configuration.builder().chunkedEncodingEnabled(false) 적용 → OCI putObject 정상."),
                ),
                solutionTitle = "OCI 용 chunkedEncodingEnabled(false)",
                attachments = listOf(
                    SeedAtt(Att.LOG, "oci-403.log", "403 응답 로그", null,
                        """PUT /error-archive-attachments/attachments/f0/f0c12ab9
< HTTP/1.1 403 Forbidden
< x-amz-request-id: ...
body: There is a required information to complete authentication that was not provided."""),
                ),
            ))
            add(RichSeed(
                title = "환불 동시 요청 시 예약 초과(Repeatable Read 스냅샷)",
                project = "core-api", status = ErrorCaseStatus.OPEN, severityCode = 1, daysAgo = 6, hoursAgo = 8,
                tags = listOf("concurrency", "mysql", "isolation", "refund"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 동시 환불 2건 → 가용액 초과 예약
payment balance=10000
req A: read balance(비잠금)=10000 -> reserve 8000
req B: read balance(비잠금)=10000 -> reserve 8000   (A 를 못 봄: RR 스냅샷 고정)
committed reserved=16000 > 10000  ← 초과
""",
                description = "동시 환불 요청이 잠금 전 비잠금 read 로 가용액을 읽어 RR 스냅샷에 고정됨 → in-flight 합산 가드가 샘. 예약을 잠긴 Payment 행의 reserved_refund 컬럼으로 이동해 해결 예정(prod DDL 필요).",
                steps = listOf(
                    SeedStep("RR 스냅샷 격리 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "두 트랜잭션이 서로의 in-flight 예약을 못 봄 — RR 스냅샷이 트랜잭션 시작 시점으로 고정되기 때문."),
                    SeedStep("잠긴 행 컬럼으로 예약 이동(설계)", StepStatus.IN_PROGRESS, "CODE_CHANGE",
                        "SELECT ... FOR UPDATE 로 Payment 행을 잠그고 reserved_refund 컬럼을 증분 → 합산이 항상 최신.",
                        insight = "'읽고 나서 쓰기' 사이의 갭은 비잠금 read 로는 못 막는다 — 잠긴 행의 상태로 합산을 옮겨야 함."),
                ),
                solutionTitle = null,
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "무중단 배포 중 순단 발생 (Recreate 전략)",
                project = "infra", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 15, hoursAgo = 0,
                tags = listOf("k8s", "deploy", "rolling", "zero-downtime"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 배포 중 프로브 (5xx 관측)
strategy: Recreate
old pod terminated at t=0, new pod Ready at t=6s
=> t=0..6s 동안 엔드포인트 없음 → 502/503
""",
                description = "배포 전략이 Recreate 라 구 파드 종료~새 파드 Ready 사이 약 6초 순단(502/503). RollingUpdate(maxUnavailable 0, maxSurge 1) + readiness 게이트 + preStop sleep 으로 0초 전환.",
                steps = listOf(
                    SeedStep("순단 구간 측정", StepStatus.RESOLVED, "INVESTIGATION",
                        "배포 중 1초 간격 프로브로 502/503 약 6초 관측. Recreate 가 구 파드를 먼저 내리는 게 원인."),
                    SeedStep("RollingUpdate + readiness 게이트", StepStatus.RESOLVED, "CONFIG",
                        "maxUnavailable=0/maxSurge=1 + readinessProbe 통과 후에만 트래픽. preStop sleep 5s 로 in-flight 드레인.",
                        insight = "무중단의 핵심은 '새 파드가 Ready 된 뒤에 구 파드를 내린다' + 'LB 가 종료 중 파드로 안 보낸다'."),
                ),
                solutionTitle = "RollingUpdate + readiness 게이트 + preStop 드레인",
                attachments = listOf(
                    SeedAtt(Att.CHART, "deploy-5xx.svg", "배포 중 5xx", "Recreate 6s 순단 → 0",
                        svgChart("배포 중 5xx/s (전/후)", "err/s", listOf(0, 0, 14, 12, 9, 0, 0, 0), "#10b981")),
                ),
            ))
        }
    }
}
