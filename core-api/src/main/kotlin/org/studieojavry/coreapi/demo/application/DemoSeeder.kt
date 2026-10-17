package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
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
 * 각 케이스가 스택트레이스(스냅샷) + 코드블록이 담긴 재현/조사 스텝 + 솔루션 + 첨부(스크린샷 SVG·JSON·로그)를
 * 갖춰, 둘러보기 방문자가 제품 사용감을 바로 체감하도록 한다. 스텝/설명 본문은 마크다운(코드펜스 하이라이팅).
 *
 * - 가시성 PUBLIC: Search 탭(전역 PUBLIC)에서 누구나 조회.
 * - 워크스페이스는 데모 WS 로 묶여 reset 이 ws 단위 wipe+reseed(스텝/솔루션/첨부는 purge 가 cascade).
 * - 첨부는 S3 put 필요 — 스토리지 미가용(테스트)이면 `demo.seed-attachments=false`.
 */
@Service
class DemoSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val codeSnippetRepository: CodeSnippetRepositoryPort,
    private val meTooRepository: CaseMeTooRepositoryPort,
    private val storagePort: AttachmentStoragePort,
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    /** 현재 시드 콘텐츠 버전 태그 — 모든 시드 케이스에 부착. 내용이 바뀌면 SEED_VERSION 을 올려 재시드를 유발. */
    val versionTag: String get() = VERSION_TAG

    @Transactional
    fun ensureSeeded(workspaceId: Long, botUserId: Long): Int {
        if (errorCaseRepository.search(criteria(workspaceId, 1)).isNotEmpty()) return 0
        return seedFresh(workspaceId, botUserId)
    }

    @Transactional
    fun seedFresh(workspaceId: Long, botUserId: Long): Int {
        SEED.forEach { seedOne(it, workspaceId, botUserId) }
        return SEED.size
    }

    private fun seedOne(s: RichSeed, workspaceId: Long, botUserId: Long) {
        // 스니펫(DB 전용) + 첨부(S3). 둘 다 markerId 로 먼저 저장돼야 case.save 가 링크할 수 있다.
        val snippets = s.snippets.mapNotNull { buildSnippet(it, botUserId) }
        val attachments = if (props.seedAttachments) s.attachments.mapNotNull { buildAttachment(it, botUserId) } else emptyList()

        val snapshot = buildSnapshot(s)
        val meta = Meta.create(workspaceId = workspaceId, severityCode = s.severityCode)
        val occurredAt = LocalDateTime.now().minusDays(s.daysAgo).minusHours(s.hoursAgo)

        // 본문에 스니펫/첨부를 인라인 임베드(@snippet/@attach) — 렌더러가 해당 위치에 코드/이미지/파일을 끼워 넣는다.
        val description = s.description + buildString {
            snippets.forEach { append("\n\n@snippet(").append(it.markerId).append(")") }
            attachments.forEach { append("\n\n@attach(").append(it.markerId).append(")") }
        }

        val case = ErrorCase.create(
            ownerUserId = botUserId,
            title = s.title,
            project = s.project,
            snapshot = snapshot,
            description = description,
            meta = meta,
            visibility = Visibility.PUBLIC,
            snippets = snippets,
            attachments = attachments,
            tags = emptyList(), // 태그는 case.save 가 아니라 tagRepository 로 별도 저장(아래)
            occurredAt = occurredAt,
        )
        applyStatus(case, s.status, botUserId)
        val caseId = errorCaseRepository.save(case).id!!

        // 태그는 별도 테이블 — 콘텐츠 태그 + 버전 태그를 정규화해 저장(버전 태그로 재시드 감지).
        (s.tags + VERSION_TAG).mapNotNull { ErrorCase.normalizeTag(it) }.distinct()
            .forEach { tagRepository.add(caseId, it) }
        val stepIds = s.steps.mapIndexed { i, st ->
            stepRepository.save(
                Step.create(
                    errorCaseId = caseId, authorUserId = botUserId, orderIndex = i,
                    title = st.title, status = st.status, attemptType = st.attemptType,
                    body = st.body, insight = st.insight,
                ),
            ).id!!
        }
        // 솔루션은 **실제 해결 스텝(비-INVESTIGATION)**만 묶는다 — 조사 스텝은 솔루션 선택 시 흐려져(dim)
        // "이 스텝들이 실제 해결책"이 드러난다. (전부 포함하면 흐려질 '나머지'가 없어 기능이 안 보임.)
        if (s.solutionTitle != null) {
            val solutionStepIds = s.steps.zip(stepIds)
                .filter { (st, _) -> st.attemptType != "INVESTIGATION" }
                .map { it.second }
                .ifEmpty { stepIds }
            if (solutionStepIds.isNotEmpty()) {
                solutionRepository.save(Solution.create(caseId, botUserId, s.solutionTitle, solutionStepIds))
            }
        }

        // 댓글(봇 작성 — 작성자 resolve 보장). 일부는 대댓글 포함.
        s.comments.forEach { c ->
            val top = commentRepository.save(
                Comment.create(caseId, botUserId, null, c.body, QuoteSourceKind.NONE, null, null),
            )
            c.reply?.let {
                commentRepository.save(
                    Comment.create(caseId, botUserId, top.id, it, QuoteSourceKind.NONE, null, null),
                )
            }
        }

        // "나도 겪음"(me-too) — 심각도 기반으로 그럴듯한 수를 가짜 distinct userId 로 채움(프로필 resolve 안 함 → 안전).
        repeat(meTooCountFor(s)) { i -> meTooRepository.add(caseId, ME_TOO_BASE_UID + i) }

        // createdAt 백데이트 — 리스트 날짜가 자연스럽게 분산되도록(create 는 now 고정 → reconstitute+update).
        val resolved = s.status == ErrorCaseStatus.RESOLVED
        errorCaseRepository.update(
            ErrorCase.reconstitute(
                id = caseId, ownerUserId = botUserId, title = s.title, project = s.project,
                snapshot = snapshot, description = description, meta = meta, visibility = Visibility.PUBLIC,
                snippets = emptyList(), attachments = emptyList(), tags = emptyList(),
                status = s.status, occurredAt = occurredAt, createdAt = occurredAt, updatedAt = occurredAt,
                resolvedAt = if (resolved) occurredAt else null,
                resolvedByUserId = if (resolved) botUserId else null,
            ),
        )
    }

    private fun buildSnippet(sn: SeedSnippet, botUserId: Long): CodeSnippet? = runCatching {
        codeSnippetRepository.save(
            CodeSnippet(
                markerId = markerIdGenerator.generateSnippetMarkerId(),
                title = sn.title, language = sn.language, filePathOrClass = sn.file, lineRange = sn.lineRange,
                caption = sn.caption, code = sn.code, uploadedByUserId = botUserId, uploadedAt = Instant.now(),
            ),
        )
    }.getOrElse { log.warn { "demo seed snippet skipped (${sn.title}): ${it.message}" }; null }

    /** 심각도 기반 me-too 수(S1 多 → S4 少) + 제목 해시 지터로 케이스마다 다른 '그럴듯한' 수. */
    private fun meTooCountFor(s: RichSeed): Int {
        val base = when (s.severityCode) { 1 -> 17; 2 -> 10; 3 -> 5; else -> 2 }
        return base + Math.floorMod(s.title.hashCode(), 7)
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
        return ErrorSnapshot(
            rawPaste = s.stackTrace.trim(),
            exceptionClass = s.exceptionClass,
            exceptionMessage = s.exceptionMessage,
            rawStackTrace = raw,
            fingeprint = FingerprintGenerator.generate(s.exceptionClass, raw),
        )
    }

    private fun buildAttachment(a: SeedAtt, botUserId: Long): Attachment? {
        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = "attachments/${markerId.take(2).ifBlank { "00" }}/$markerId"
        val bytes = a.content.toByteArray(Charsets.UTF_8)
        return runCatching {
            storagePort.put(objectKey, bytes, a.contentType)
            attachmentRepository.save(
                Attachment(
                    markerId = markerId, title = a.title, caption = a.caption, fileName = a.fileName,
                    contentType = a.contentType, size = bytes.size.toLong(), kind = a.kind,
                    objectKey = objectKey, uploadedByUserId = botUserId, uploadedAt = Instant.now(),
                ),
            )
        }.getOrElse { log.warn { "demo seed attachment skipped (${a.fileName}): ${it.message}" }; null }
    }

    private fun criteria(workspaceId: Long, limit: Int) = ErrorCaseSearchCriteria(
        workspaceId = workspaceId, ownerUserId = null, status = null, fingerprint = null,
        visibility = null, cursorCreatedAt = null, cursorId = null, limit = limit,
    )

    private enum class Att { CHART, TERMINAL, JSON, LOG }

    private class SeedAtt(type: Att, val fileName: String, val title: String, val caption: String?, val content: String) {
        val contentType = when (type) { Att.CHART, Att.TERMINAL -> "image/svg+xml"; Att.JSON -> "application/json"; Att.LOG -> "text/plain" }
        val kind = when (type) { Att.CHART, Att.TERMINAL -> AttachmentKind.IMAGE; Att.JSON -> AttachmentKind.JSON; Att.LOG -> AttachmentKind.TEXT }
    }

    private class SeedStep(val title: String, val status: StepStatus, val attemptType: String, val body: String, val insight: String? = null)

    private class SeedComment(val body: String, val reply: String? = null)

    private class SeedSnippet(
        val title: String,
        val language: String,
        val code: String,
        val file: String? = null,
        val lineRange: String? = null,
        val caption: String? = null,
    )

    private class RichSeed(
        val title: String, val project: String, val status: ErrorCaseStatus, val severityCode: Int?,
        val daysAgo: Long, val hoursAgo: Long, val tags: List<String>,
        val exceptionClass: String?, val exceptionMessage: String?, val stackTrace: String,
        val description: String, val steps: List<SeedStep>, val solutionTitle: String?, val attachments: List<SeedAtt>,
        val comments: List<SeedComment> = emptyList(),
        val snippets: List<SeedSnippet> = emptyList(),
    )

    companion object {
        /** 시드 콘텐츠 버전 — 개수가 같아도 **내용이 바뀌면 이 값을 올려** 배포 시 자동 재시드를 유발한다. */
        private const val SEED_VERSION = 6
        private val VERSION_TAG = "seed-v$SEED_VERSION"

        /** me-too 시드용 가짜 사용자 id 기준값(실 사용자와 충돌 없게 큼). 프로필 resolve 안 됨. */
        private const val ME_TOO_BASE_UID = 900_000L

        private fun svgChart(title: String, unit: String, values: List<Int>, accent: String = "#ef4444"): String {
            val w = 720; val h = 360; val padL = 56; val padB = 44; val padT = 56; val padR = 24
            val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(1)
            val plotW = w - padL - padR; val plotH = h - padT - padB
            val stepX = if (values.size > 1) plotW.toDouble() / (values.size - 1) else 0.0
            val pts = values.mapIndexed { i, v -> "%.1f,%.1f".format(padL + stepX * i, padT + plotH - (v.toDouble() / maxV) * plotH) }.joinToString(" ")
            val lastX = padL + stepX * values.lastIndex
            val lastY = padT + plotH - (values.last().toDouble() / maxV) * plotH
            val grid = (0..4).joinToString("") { g ->
                val y = padT + plotH - plotH * g / 4
                """<line x1="$padL" y1="$y" x2="${w - padR}" y2="$y" stroke="#e5e7eb" stroke-width="1"/><text x="${padL - 8}" y="${y + 4}" font-size="11" fill="#9ca3af" text-anchor="end">${maxV * g / 4}</text>"""
            }
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" font-family="ui-sans-serif,system-ui,sans-serif"><rect width="$w" height="$h" rx="14" fill="#ffffff" stroke="#e5e7eb"/><text x="$padL" y="30" font-size="16" font-weight="700" fill="#111827">$title</text><text x="${w - padR}" y="30" font-size="12" fill="#9ca3af" text-anchor="end">$unit</text>$grid<polyline points="$pts" fill="none" stroke="$accent" stroke-width="2.5" stroke-linejoin="round"/><circle cx="%.1f" cy="%.1f" r="4.5" fill="$accent"/></svg>""".format(lastX, lastY)
        }

        private fun svgTerminal(title: String, lines: List<String>): String {
            val w = 760; val lineH = 20; val top = 54
            val h = top + lines.size * lineH + 18
            val body = lines.mapIndexed { i, raw ->
                val color = when {
                    raw.contains("ERROR") || raw.contains("Exception") -> "#f87171"
                    raw.trimStart().startsWith("at ") -> "#9ca3af"
                    raw.contains("WARN") -> "#fbbf24"
                    raw.trimStart().startsWith("+") -> "#34d399"
                    raw.trimStart().startsWith("-") -> "#f87171"
                    else -> "#d1d5db"
                }
                val safe = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                """<text x="18" y="${top + i * lineH}" font-size="12.5" font-family="ui-monospace,Menlo,monospace" fill="$color">$safe</text>"""
            }.joinToString("\n")
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h"><rect width="$w" height="$h" rx="12" fill="#0b0f17"/><rect width="$w" height="34" rx="12" fill="#161b26"/><circle cx="20" cy="17" r="5" fill="#ef4444"/><circle cx="40" cy="17" r="5" fill="#f59e0b"/><circle cx="60" cy="17" r="5" fill="#22c55e"/><text x="${w - 14}" y="21" font-size="12" fill="#6b7280" text-anchor="end" font-family="ui-monospace,monospace">$title</text>$body</svg>"""
        }

        private val SEED: List<RichSeed> = buildList {
            add(RichSeed(
                title = "로그인 직후 홈 진입에서 간헐적 NullPointerException",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 1, hoursAgo = 3,
                tags = listOf("kotlin", "npe", "oauth", "dashboard"),
                exceptionClass = "java.lang.NullPointerException",
                exceptionMessage = "Cannot invoke \"String.length()\" because \"avatarUrl\" is null",
                stackTrace = """
java.lang.NullPointerException: Cannot invoke "String.length()" because "avatarUrl" is null
    at org.studieojavry.coreapi.home.ProfileHydrator.hydrate(ProfileHydrator.kt:48)
    at org.studieojavry.coreapi.home.HomeDashboardService.recentActive(HomeDashboardService.kt:72)
    at org.studieojavry.coreapi.home.HomeController.recentActiveCases(HomeController.kt:39)
""",
                description = """
### 증상
GitHub OAuth 로그인 **직후** 홈 대시보드 진입에서 간헐적으로 500 이 뜬다. 응답은 `500 INTERNAL_ERROR`, 서버 로그엔 `NullPointerException`.

### 영향
- **신규 가입자** 에게 집중 발생(아바타 미설정 계정).
- 홈 첫 화면이 깨져 **온보딩 이탈** 유발.

### 1차 가설
프로필 하이드레이션이 `avatarUrl` 을 non-null 로 가정 → iam 응답에 `avatarUrl: null` 이 오면 터진다.
""",
                steps = listOf(
                    SeedStep("신규/기존 계정으로 재현 범위 좁히기", StepStatus.RESOLVED, "INVESTIGATION", """
아바타가 **없는** 신규 계정 3개 vs 기존 계정 3개로 로그인하며 관찰:

```text
newbie-a (avatar=null)  -> 500  (재현)
newbie-b (avatar=null)  -> 500  (재현)
veteran-a (avatar set)  -> 200  (정상)
```

신규(아바타 null)에서만 100% 재현 → `avatarUrl == null` 분기로 좁혀짐.
""", insight = "재현 조건을 '계정 속성'으로 분리하면 코드 경로가 바로 드러난다."),
                    SeedStep("원인 지점 확인 — ProfileHydrator", StepStatus.RESOLVED, "INVESTIGATION", """
스택 최상단 `ProfileHydrator.kt:48` 확인:

```kotlin
// 문제 코드 — avatarUrl 을 non-null 로 단정
fun hydrate(p: IamPublicProfile): AuthorCard {
    val initials = p.avatarUrl().length  // avatarUrl 이 null 이면 NPE
    return AuthorCard(p.displayName(), p.avatarUrl(), initials)
}
```

iam 응답 샘플(첨부 `iam-profile-response.json`)에서 `avatarUrl` 이 `null` 로 내려온 것을 확인.
"""),
                    SeedStep("널 가드 + 기본 아바타 폴백", StepStatus.RESOLVED, "CODE_CHANGE", """
외부 필드는 nullable 로 받고 경계에서 기본값 처리:

```diff
- val initials = p.avatarUrl().length
- return AuthorCard(p.displayName(), p.avatarUrl(), initials)
+ val avatar = p.avatarUrl() ?: defaultAvatar(p.displayName())
+ return AuthorCard(p.displayName(), avatar, p.displayName().take(2))
```

회귀 테스트:

```kotlin
@Test fun `avatarUrl 이 null 이어도 하이드레이션 성공`() {
    val card = hydrator.hydrate(profile(avatarUrl = null))
    assertThat(card.avatarUrl).isEqualTo(defaultAvatar("Newbie"))
}
```
""", insight = "외부(iam) 응답 필드는 전부 nullable 로 받고 경계에서 기본값으로 방어하는 게 안전하다."),
                ),
                solutionTitle = "avatarUrl null 가드 + 기본 아바타 폴백 + 회귀 테스트",
                attachments = listOf(
                    SeedAtt(Att.TERMINAL, "npe-stacktrace.svg", "에러 스택트레이스", "홈 진입 시 NPE", svgTerminal("core-api · stdout", listOf(
                        "2026-10-16 14:02:11 ERROR HomeController - 500 /users/me/recent-active-cases",
                        "java.lang.NullPointerException: avatarUrl is null",
                        "    at ProfileHydrator.hydrate(ProfileHydrator.kt:48)",
                        "    at HomeDashboardService.recentActive(HomeDashboardService.kt:72)",
                        "    at HomeController.recentActiveCases(HomeController.kt:39)"))),
                    SeedAtt(Att.JSON, "iam-profile-response.json", "iam 프로필 응답", "avatarUrl 이 null 인 신규 계정", """{
  "userId": 10432,
  "handle": "newbie-dev",
  "displayName": "Newbie Dev",
  "avatarUrl": null,
  "bio": null
}"""),
                ),
                comments = listOf(
                    SeedComment(
                        body = "저희도 신규 가입 직후 홈에서 동일 NPE 겪었어요. avatarUrl 이 null 인 계정에서만 재현되는 것까지 똑같네요.",
                        reply = "외부(iam) 응답 필드는 전부 nullable 로 받고 경계에서 기본값 처리하도록 정리했습니다. 이후 재발 없어요.",
                    ),
                    SeedComment(body = "회귀 테스트까지 있어서 좋네요 👍 비슷한 하이드레이션 지점 다른 곳도 점검하면 좋겠습니다."),
                ),
                snippets = listOf(
                    SeedSnippet(
                        title = "재현 단위 테스트",
                        language = "kotlin",
                        file = "ProfileHydratorTest.kt",
                        caption = "avatarUrl=null 일 때 기존 코드가 NPE 를 던짐을 고정",
                        code = "@Test\nfun `avatarUrl 이 null 이면 기존 코드가 NPE`() {\n    val profile = profile(avatarUrl = null)\n    assertThrows<NullPointerException> { legacyHydrate(profile) }\n}",
                    ),
                ),
            ))
            add(RichSeed(
                title = "피크 시간마다 터지는 503 — HikariCP 커넥션 풀 고갈",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 1, daysAgo = 4, hoursAgo = 2,
                tags = listOf("hikaricp", "connection-pool", "jpa", "performance"),
                exceptionClass = "org.springframework.dao.DataAccessResourceFailureException",
                exceptionMessage = "HikariPool-1 - Connection is not available, request timed out after 30000ms",
                stackTrace = """
org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
""",
                description = """
### 증상
트래픽 피크(오후 8시경)마다 `/error-cases` 조회가 **503 으로 무더기 실패**. 30초 타임아웃 후 응답.

### 핵심 지표
```text
hikaricp_connections_active   10 / 10   (max 고갈)
hikaricp_connections_pending  37        (대기 폭증)
avg query time                12ms      (쿼리는 빠름!)
```

쿼리는 빠른데 풀이 고갈 → **커넥션 보유 시간**이 문제. `open-in-view` 가 뷰 렌더까지 커넥션을 쥐고 있는 게 의심된다.
""",
                steps = listOf(
                    SeedStep("풀 메트릭으로 고갈 확인", StepStatus.RESOLVED, "INVESTIGATION", """
Grafana 에서 피크 구간 관찰 — active 가 10/10 에 붙고 pending 누적(첨부 차트):

```text
20:10  active=10  pending=37  timeouts=18
20:11  active=10  pending=41  timeouts=25
```

평균 쿼리 12ms 인데 커넥션이 안 돌아옴 → 쿼리 밖에서 커넥션을 오래 잡는다.
"""),
                    SeedStep("OSIV 가 범인인지 검증", StepStatus.RESOLVED, "INVESTIGATION", """
`open-in-view` 기본 `true` 확인. 요청 전체 생명주기 동안 커넥션 보유:

```yaml
spring:
  jpa:
    open-in-view: true   # 기본값 — 뷰 렌더까지 커넥션 점유
```

Flight Recorder 로 커넥션 보유 구간이 컨트롤러 반환 후까지 이어짐을 확인.
""", insight = "OSIV 는 지연로딩 편의 대신 커넥션을 요청 끝까지 잡아 풀 고갈의 흔한 원인."),
                    SeedStep("OSIV off + N+1 제거 + 풀 재튜닝", StepStatus.RESOLVED, "CODE_CHANGE", """
```yaml
spring:
  jpa:
    open-in-view: false
  datasource:
    hikari:
      maximum-pool-size: 20     # (core * 2) + effective_spindle_count 기준 재산정
      connection-timeout: 3000
```

작성자 조회 N+1 제거:

```diff
- cases.forEach { it.author = iam.getProfile(it.ownerId) }   // N 번
+ val authors = iam.getProfiles(cases.map { it.ownerId })     // 1 번 batch
```
""", insight = "풀 크기를 키우기 전에 '커넥션 보유 시간'부터 줄여라 — 안 그러면 더 큰 풀도 금방 고갈."),
                ),
                solutionTitle = "OSIV off + N+1 batch + 풀 재튜닝으로 커넥션 보유시간 단축",
                attachments = listOf(
                    SeedAtt(Att.CHART, "pool-active.svg", "활성 커넥션", "피크에 10/10 고갈", svgChart("HikariCP active connections", "conns", listOf(2, 3, 5, 8, 10, 10, 10, 4), "#f59e0b")),
                    SeedAtt(Att.LOG, "hikari-timeout.log", "타임아웃 로그", null, """2026-10-13 20:11:03.412 WARN  HikariPool-1 - Connection is not available, request timed out after 30000ms
2026-10-13 20:11:03.980 ERROR ErrorCaseController - 503 GET /api/v1/error-cases (ws=public)
2026-10-13 20:11:04.117 WARN  HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=31s)"""),
                ),
                comments = listOf(
                    SeedComment(
                        body = "풀 크기만 20→40 으로 올려봤는데 피크 때 똑같이 고갈되더라고요. 역시 보유 시간이 문제였네요.",
                        reply = "맞습니다. 풀을 키우기 전에 open-in-view off + N+1 제거로 '커넥션 쥐는 시간'부터 줄이는 게 핵심이었어요.",
                    ),
                    SeedComment(body = "hikaricp_connections_pending 알럿을 추가해두면 다음 피크 전에 미리 잡을 수 있을 것 같아요."),
                ),
            ))
            add(RichSeed(
                title = "PDF 공개 export 중 OutOfMemoryError (Java heap space)",
                project = "publish-api", status = ErrorCaseStatus.IN_PROGRESS, severityCode = 1, daysAgo = 0, hoursAgo = 5,
                tags = listOf("jvm", "oom", "pdf", "memory"),
                exceptionClass = "java.lang.OutOfMemoryError", exceptionMessage = "Java heap space",
                stackTrace = """
java.lang.OutOfMemoryError: Java heap space
    at com.openhtmltopdf.pdfboxout.PdfBoxRenderer.renderPage(PdfBoxRenderer.java:612)
    at org.studieojavry.publishapi.export.PdfExporter.export(PdfExporter.kt:57)
    at org.studieojavry.publishapi.publishment.PublicArticleController.exportPdf(PublicArticleController.kt:88)
""",
                description = """
### 증상
이미지가 많은 공개 아티클을 **PDF export** 할 때 컨테이너(1 vCPU / 512MB)가 힙을 소진해 OOM. 동시 2건이면 즉사.

### 힙 덤프 요약
```text
BufferedImage            357 MB   (70.1%)   <- 원본 해상도 이미지 통째 로드
byte[] (PDFBox COSStream) 92 MB   (18.0%)
그 외                      63 MB
```
""",
                steps = listOf(
                    SeedStep("힙 덤프로 점유 주체 특정", StepStatus.RESOLVED, "INVESTIGATION", """
```bash
java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof -jar app.jar
# 재현 후 Eclipse MAT 로 분석
```

`BufferedImage` 가 힙의 70% → 원본 해상도 이미지를 렌더 중 통째로 들고 있음.
"""),
                    SeedStep("렌더 동시성 1로 직렬화(완화)", StepStatus.IN_PROGRESS, "CONFIG", """
export 전용 세마포어로 PDF 렌더를 직렬화:

```kotlin
private val exportGate = Semaphore(1)   // 1 vCPU 박스: 동시 렌더 금지
fun export(slug: String): ByteArray {
    exportGate.acquire()
    try { return renderer.render(load(slug)) } finally { exportGate.release() }
}
```

피크 OOM 빈도 급감. 단, **대형 문서 1건** 은 여전히 아슬아슬(근본 해결 아님).
"""),
                    SeedStep("업로드 시 렌더용 다운스케일 사본(설계)", StepStatus.IN_PROGRESS, "CODE_CHANGE", """
첨부 저장 시 장변 2000px 리사이즈 사본을 만들어 PDF 렌더엔 사본만 사용:

```kotlin
val scaled = Thumbnails.of(original).size(2000, 2000).asBufferedImage()
storage.put(renderKeyOf(markerId), scaled.toPngBytes(), "image/png")
```

(진행 중 — 저장 파이프라인에 훅 추가 필요.)
""", insight = "렌더 입력 크기를 업로드 시점에 제한하면 런타임 메모리 상한이 예측 가능해진다."),
                ),
                solutionTitle = null,
                attachments = listOf(
                    SeedAtt(Att.CHART, "heap-usage.svg", "힙 사용량", "export 구간 급상승 → OOM", svgChart("JVM Heap Used (export 구간)", "MB", listOf(180, 210, 240, 300, 380, 470, 512, 512), "#ef4444")),
                ),
            ))
            add(RichSeed(
                title = "쿠폰 발급 동시성 — 멀티 인스턴스에서 한도 초과 발급",
                project = "core-api", status = ErrorCaseStatus.IN_PROGRESS, severityCode = 1, daysAgo = 8, hoursAgo = 1,
                tags = listOf("concurrency", "redis", "redisson", "lock"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 부하 테스트 (인스턴스 2대, VU=100)
coupon_id=SUMMER2026 limit=1000
instance-a issued=623
instance-b issued=502
total issued=1125  (EXPECTED <= 1000)   <- 125 초과
root-cause: check-then-update race, 공유 락 없음
""",
                description = """
### 증상
한정 수량(1000) 쿠폰을 **2개 인스턴스** 가 공유 DB 에서 발급할 때 **1125건** 발급(125 초과).

### 레이스 타임라인
```text
T1  A: SELECT issued(=990)  < 1000  -> OK
T2  B: SELECT issued(=990)  < 1000  -> OK   (A 의 증분을 아직 못 봄)
T3  A: UPDATE issued = 991
T4  B: UPDATE issued = 991            <- 둘 다 통과 → 초과
```
확인(check)과 증분(update) 사이에 **원자성/직렬화가 없다.**
""",
                steps = listOf(
                    SeedStep("2-인스턴스 부하로 초과 재현", StepStatus.RESOLVED, "INVESTIGATION", """
```bash
k6 run -e INSTANCES=2 -e VUS=100 -e LIMIT=1000 coupon_issue.js
# → issued_total=1125, over=125 (단일 인스턴스에선 재현 안 됨)
```
분산 race 확정.
"""),
                    SeedStep("Redisson 분산 락으로 임계구역 직렬화", StepStatus.IN_PROGRESS, "DEPENDENCY", """
```kotlin
val lock = redisson.getLock("coupon:lock:" + couponId)
lock.lock(3, TimeUnit.SECONDS)
try {
    val issued = repo.countIssued(couponId)
    require(issued < limit) { "sold out" }
    repo.issue(couponId, userId)
} finally { lock.unlock() }
```
초과는 사라졌으나 **Redis 가 단일 장애점** 이 됨.
"""),
                    SeedStep("Redis 장애 fallback — DB 조건부 UPDATE", StepStatus.IN_PROGRESS, "CODE_CHANGE", """
정확성의 최종 보루는 DB 제약에 둔다:

```sql
UPDATE coupon_stock
   SET issued = issued + 1
 WHERE coupon_id = :id AND issued < :limit;   -- 0 rows = 매진
```
`UNIQUE(coupon_id, user_id)` 로 1인 1매 보장. Redis 불가 시 이 경로로 자동 전환.
""", insight = "분산 락은 성능 경로, 정확성의 최종 보루는 DB 제약 — 락과 제약의 이중화."),
                ),
                solutionTitle = null,
                attachments = listOf(
                    SeedAtt(Att.CHART, "coupon-overissue.svg", "발급 누적", "한도 1000 초과(1125)", svgChart("쿠폰 발급 누적 (2 인스턴스)", "issued", listOf(120, 320, 560, 780, 940, 1040, 1125), "#ef4444")),
                    SeedAtt(Att.JSON, "loadtest-summary.json", "부하 테스트 요약", null, """{
  "scenario": "coupon-issue-2instances",
  "vus": 100, "duration": "60s", "limit": 1000,
  "issued_total": 1125, "over_issued": 125,
  "per_instance": { "a": 623, "b": 502 }
}"""),
                ),
            ))
            add(RichSeed(
                title = "알림 SSE 연결이 약 30초마다 끊김",
                project = "noti-api", status = ErrorCaseStatus.RESOLVED, severityCode = 3, daysAgo = 2, hoursAgo = 6,
                tags = listOf("sse", "timeout", "realtime", "nginx"),
                exceptionClass = "java.io.IOException", exceptionMessage = "Broken pipe",
                stackTrace = """
java.io.IOException: Broken pipe
    at java.base/sun.nio.ch.SocketDispatcher.write0(Native Method)
    at org.studieojavry.notiapi.sse.NotificationSseBroker.deliverToLocal(NotificationSseBroker.kt:64)
""",
                description = """
### 증상
`EventSource` 가 약 **30초 간격** 으로 재연결 반복. 브라우저 네트워크 탭:
```text
200 text/event-stream  (30.1s)  -> closed
200 text/event-stream  (29.9s)  -> closed
```
서버 heartbeat 는 45초 주기 → 프록시 idle timeout(30s)이 먼저 끊는다.
""",
                steps = listOf(
                    SeedStep("재연결 주기와 프록시 타임아웃 대조", StepStatus.RESOLVED, "INVESTIGATION", """
```nginx
# 기존 — SSE 경로에 keepalive 여유 없음
proxy_read_timeout 30s;
```
heartbeat(45s) > idle timeout(30s) → 트래픽 없는 30초 구간에서 프록시가 연결 종료.
"""),
                    SeedStep("heartbeat 를 15초로 단축", StepStatus.RESOLVED, "CODE_CHANGE", """
주석 프레임(`:ping`)을 timeout 아래로:

```kotlin
@Scheduled(fixedRate = 15_000)   // < proxy idle 30s
fun heartbeat() = emitters.forEach { it.send(SseEmitter.event().comment("ping")) }
```
재연결 사라짐.
""", insight = "SSE 안정성의 핵심은 'heartbeat 주기 < 경로상 최소 idle timeout'."),
                ),
                solutionTitle = "heartbeat 주기를 프록시 idle timeout 아래로 조정",
                attachments = listOf(
                    SeedAtt(Att.LOG, "sse-reconnect.log", "재연결 로그", null, """[client] EventSource opened  t=0.00s
[client] EventSource error -> reconnect  t=30.12s
[client] EventSource opened  t=30.20s
[client] EventSource error -> reconnect  t=60.05s"""),
                ),
            ))
            add(RichSeed(
                title = "SSE 알림이 다른 노드 접속 사용자에게 도달하지 않음",
                project = "noti-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 3, hoursAgo = 4,
                tags = listOf("sse", "redis", "pubsub", "scaling"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 노드 2대, SseEmitter 는 접속 노드에만 등록
A-node: createNotification(userId=42) -> local emitters: []   (user 42 는 B-node 접속)
B-node: emitters[42]=1  but never receives
=> 교차 노드 전달 경로 없음
""",
                description = """
### 증상
알림을 **생성한 노드** 와 사용자가 **접속한 노드** 가 다르면 SSE 가 전달되지 않음.
`SseEmitter` 레지스트리가 노드-로컬이라, A노드 이벤트가 B노드 접속자에게 안 간다.

### 해결 방향
노드-로컬 emitter 는 유지하되, **트리거만 Redis pub/sub 로 브로드캐스트** → 모든 노드가 자기 로컬로 전달.
""",
                steps = listOf(
                    SeedStep("구독자 수로 교차 미전달 확인", StepStatus.RESOLVED, "INVESTIGATION", """
```bash
redis-cli PUBSUB NUMSUB noti:realtime
# 1) "noti:realtime"
# 2) (integer) 0      <- 아무도 구독 안 함 → 교차 전달 불가
```
"""),
                    SeedStep("Redis 채널 fan-out 도입", StepStatus.RESOLVED, "CODE_CHANGE", """
생성 시 채널에 publish, 전 노드가 구독:

```kotlin
// publish (생성 노드)
redis.convertAndSend("noti:realtime", RealtimeEnvelope(userId, eventId, json))

// subscribe (모든 노드) -> 자기 로컬 emitter 로 전달
override fun onMessage(msg: Message, pattern: ByteArray?) {
    val env = decode(msg.body)
    broker.deliverToLocal(env.userId, env.eventId, env.data)
}
```
""", insight = "노드-로컬 상태(emitter)는 유지하되 트리거만 pub/sub 로 브로드캐스트하면 수평확장에서 안 깨진다."),
                    SeedStep("라운드트립 통합테스트", StepStatus.RESOLVED, "CODE_CHANGE", """
```kotlin
@Test fun `publish -> 구독 -> 로컬 전달 바이트 동일`() {
    publisher.publish(env)
    val got = capturedByLocalBroker.poll(2, SECONDS)
    assertThat(got).isEqualTo(env.data)   // byte-identical
}
```
`PUBSUB NUMSUB` == 노드 수 확인.
"""),
                ),
                solutionTitle = "Redis pub/sub 로 노드 간 SSE fan-out",
                attachments = listOf(
                    SeedAtt(Att.TERMINAL, "pubsub-check.svg", "구독자 확인", "fan-out 후 2노드 구독", svgTerminal("redis-cli", listOf(
                        "> PUBSUB NUMSUB noti:realtime", "1) \"noti:realtime\"", "2) (integer) 2", "# 2 = 양 노드 구독 -> 교차 전달 OK"))),
                ),
            ))
            add(RichSeed(
                title = "추천 팔로우 조회 p95 7.4초 — cold-start full-scan fallback",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 12, hoursAgo = 2,
                tags = listOf("performance", "query", "n+1", "index"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# APM 트레이스 (suggested-followees)
GET /api/v1/users/me/suggested-followees  p95=7,450ms
  fallback: findRandomPublicCaseOwners  6,900ms  (full table scan)
  author hydration (iam)  N calls           520ms
""",
                description = """
### 증상
추천 팔로우가 **cold-start(팔로잉 0)** 에서 p95 **7.4초**. 신규 사용자 홈이 체감상 멈춘다.

### 병목 분해 (APM)
```text
total p95            7,450 ms
├─ full-scan fallback 6,900 ms  (92%)   <- 인덱스 없는 랜덤 추출
└─ author N+1         520 ms            <- 후보 수만큼 iam 호출
```
""",
                steps = listOf(
                    SeedStep("실행계획으로 full-scan 확인", StepStatus.RESOLVED, "INVESTIGATION", """
```sql
EXPLAIN ANALYZE
SELECT owner_user_id FROM error_case
WHERE visibility = 'PUBLIC' ORDER BY random() LIMIT 20;
--  Seq Scan on error_case  (cost=... rows=142000)  actual time=6900ms
```
`ORDER BY random()` + 인덱스 부재 = 전체 스캔.
"""),
                    SeedStep("fallback 쿼리 인덱스화 + 상한", StepStatus.RESOLVED, "CODE_CHANGE", """
```sql
CREATE INDEX ix_ec_public_recent
    ON error_case (created_at DESC) WHERE visibility = 'PUBLIC';
```
```diff
- ORDER BY random() LIMIT 20
+ WHERE created_at > now() - interval '30 days'
+ ORDER BY created_at DESC LIMIT 20
```
"""),
                    SeedStep("작성자 하이드레이션 N+1 → batch", StepStatus.RESOLVED, "CODE_CHANGE", """
```diff
- candidates.map { iam.getProfile(it.ownerId) }        // N 회
+ iam.getProfiles(candidates.map { it.ownerId }.distinct())  // 1 회 batch
```
결과: **p95 7,450ms → 89ms** (첨부 차트).
""", insight = "cold-start 경로는 '느린 fallback + N+1' 이 겹쳐 최악 p95 를 만든다 — 둘 다 같이 잡아야 한다."),
                ),
                solutionTitle = "fallback 인덱스화 + 작성자 batch 로 p95 7,450→89ms",
                attachments = listOf(
                    SeedAtt(Att.CHART, "p95-before-after.svg", "p95 개선", "7,450ms → 89ms", svgChart("suggested-followees p95", "ms", listOf(7450, 5200, 2600, 900, 300, 120, 89), "#10b981")),
                ),
                comments = listOf(
                    SeedComment(body = "random() 정렬이 풀스캔을 유발하는 건 놓치기 쉬운데 실행계획 첨부가 명확하네요. 저도 비슷한 랜덤 추천에서 같은 패턴 발견했어요."),
                ),
                snippets = listOf(
                    SeedSnippet(
                        title = "before: 풀스캔 실행계획",
                        language = "sql",
                        file = "suggested_followees.sql",
                        caption = "ORDER BY random() → Seq Scan",
                        code = "EXPLAIN ANALYZE\nSELECT owner_user_id FROM error_case\nWHERE visibility = 'PUBLIC'\nORDER BY random() LIMIT 20;\n-- Seq Scan on error_case (rows=142000) actual time=6900ms",
                    ),
                    SeedSnippet(
                        title = "after: 부분 인덱스 + 윈도우",
                        language = "sql",
                        file = "V__ec_public_recent.sql",
                        caption = "created_at 부분 인덱스로 Index Scan",
                        code = "CREATE INDEX ix_ec_public_recent\n  ON error_case (created_at DESC) WHERE visibility = 'PUBLIC';\n-- 조회: 최근 30일 윈도우 + created_at DESC LIMIT 20 → Index Scan, 89ms",
                    ),
                ),
            ))
            add(RichSeed(
                title = "Flyway 마이그레이션이 부팅 시 실행되지 않음 (Boot 4.0)",
                project = "iam-api", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 16, hoursAgo = 4,
                tags = listOf("flyway", "spring-boot-4", "db", "migration"),
                exceptionClass = "org.hibernate.tool.schema.spi.SchemaManagementException",
                exceptionMessage = "Schema-validation: missing table [iam_refresh_token]",
                stackTrace = """
org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing table [iam_refresh_token]
    at org.hibernate.tool.schema.internal.AbstractSchemaValidator.validateTable(AbstractSchemaValidator.java:132)
""",
                description = """
### 증상
새 환경 부팅 시 `ddl-auto=validate` 가 **없는 테이블** 에서 실패. 그런데 Flyway 의존성은 분명 들어있다.

### 단서
```sql
-- flyway_schema_history 테이블 자체가 없음 → migrate 가 한 번도 안 돌았다
SELECT * FROM flyway_schema_history;  -- ERROR: relation does not exist
```
Spring Boot **4.0** 은 autoconfig 가 모듈로 분리됨 → `flyway-core` 만으론 `FlywayAutoConfiguration` 이 안 붙는다.
""",
                steps = listOf(
                    SeedStep("migrate 미실행 확인", StepStatus.RESOLVED, "INVESTIGATION", """
```bash
./gradlew dependencies | grep flyway
# org.flywaydb:flyway-core:...    (있음)
# spring-boot-flyway              (없음!)  <- autoconfig 모듈 누락
```
"""),
                    SeedStep("spring-boot-flyway 모듈 추가", StepStatus.RESOLVED, "DEPENDENCY", """
```diff
  implementation("org.flywaydb:flyway-core")
+ implementation("org.springframework.boot:spring-boot-flyway")   // Boot 4.0 autoconfig
  implementation("org.flywaydb:flyway-database-postgresql")
```
부팅 로그에 `Flyway ... migrating schema` 등장 → 테이블 생성 확인.
""", insight = "Boot 4.0 은 autoconfig 를 모듈로 쪼갰다 — 라이브러리만 넣으면 '왜 자동설정이 안 돌지' 함정에 빠진다."),
                ),
                solutionTitle = "spring-boot-flyway autoconfig 모듈 추가",
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "이미지 업로드 시 OCI Object Storage 403 (aws-chunked)",
                project = "core-api", status = ErrorCaseStatus.CLOSED, severityCode = 3, daysAgo = 20, hoursAgo = 0,
                tags = listOf("s3", "oci", "upload", "aws-sdk"),
                exceptionClass = "software.amazon.awssdk.services.s3.model.S3Exception",
                exceptionMessage = "There is a required information to complete authentication that was not provided (403)",
                stackTrace = """
software.amazon.awssdk.services.s3.model.S3Exception: ... (Service: S3, Status Code: 403)
    at org.studieojavry.coreapi.errorcase.attachment.infrastructure.s3.S3AttachmentStorageAdapter.put(S3AttachmentStorageAdapter.kt:41)
""",
                description = """
### 증상
첨부 업로드 `putObject` 가 **OCI 에서만 403**, MinIO(local)·AWS 에선 성공. 환경별 차이로 좁혀진다.

### 요청 비교
```http
PUT /bucket/attachments/f0/f0c12ab9
x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD   <- aws-chunked
Content-Encoding: aws-chunked
< 403 Forbidden
```
AWS SDK v2 가 기본으로 **aws-chunked 스트리밍 서명** 을 쓰는데 OCI S3 호환 API 가 미지원.
""",
                steps = listOf(
                    SeedStep("공급자별 호환성으로 원인 분리", StepStatus.RESOLVED, "INVESTIGATION", """
```text
MinIO (local)  putObject -> 200
AWS   S3       putObject -> 200
OCI   S3호환    putObject -> 403   (aws-chunked 헤더 존재)
```
"""),
                    SeedStep("chunkedEncodingEnabled(false)", StepStatus.RESOLVED, "CONFIG", """
```kotlin
S3Client.builder()
    .serviceConfiguration(
        S3Configuration.builder()
            .chunkedEncodingEnabled(false)   // OCI 호환
            .pathStyleAccessEnabled(true)
            .build(),
    ).build()
```
OCI putObject 정상.
""", insight = "S3 '호환' API 는 서명/인코딩 세부에서 갈린다 — chunked encoding 은 공급자별로 먼저 의심."),
                ),
                solutionTitle = "OCI 용 chunkedEncodingEnabled(false)",
                attachments = listOf(
                    SeedAtt(Att.LOG, "oci-403.log", "403 응답", null, """> PUT /error-archive-attachments/attachments/f0/f0c12ab9
> Content-Encoding: aws-chunked
< HTTP/1.1 403 Forbidden
< body: There is a required information to complete authentication that was not provided."""),
                ),
            ))
            add(RichSeed(
                title = "무중단 배포 중 순단 발생 (Recreate 전략)",
                project = "infra", status = ErrorCaseStatus.RESOLVED, severityCode = 2, daysAgo = 15, hoursAgo = 0,
                tags = listOf("k8s", "deploy", "rolling", "zero-downtime"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 배포 중 1초 간격 프로브 (502/503 관측)
t=0   old pod Terminating
t=1..6  no endpoints  -> 502/503
t=6   new pod Ready
""",
                description = """
### 증상
배포 전략이 `Recreate` 라 구 파드 종료~새 파드 Ready 사이 **약 6초 순단**(502/503).

### 원인
```yaml
spec:
  strategy:
    type: Recreate   # 구 파드를 먼저 내리고 새 파드를 띄움 -> 공백 발생
```
""",
                steps = listOf(
                    SeedStep("순단 구간 측정", StepStatus.RESOLVED, "INVESTIGATION", """
```bash
while true; do curl -s -o /dev/null -w "%{http_code} " https://api.error-archive.com/actuator/health; sleep 1; done
# 배포 중: 200 200 502 503 503 503 503 200 200
```
약 6초 5xx.
"""),
                    SeedStep("RollingUpdate + readiness 게이트 + 드레인", StepStatus.RESOLVED, "CONFIG", """
```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }
  template:
    spec:
      containers:
        - name: app
          readinessProbe: { httpGet: { path: /internal/actuator/health/readiness, port: 8081 } }
          lifecycle:
            preStop: { exec: { command: ["sh","-c","sleep 5"] } }   # in-flight 드레인
```
배포 중 5xx **0건** (첨부 차트).
""", insight = "무중단 = '새 파드 Ready 후 구 파드 종료' + 'LB 가 종료 중 파드로 안 보냄(preStop 드레인)'."),
                ),
                solutionTitle = "RollingUpdate(maxUnavailable 0) + readiness 게이트 + preStop 드레인",
                attachments = listOf(
                    SeedAtt(Att.CHART, "deploy-5xx.svg", "배포 중 5xx", "Recreate 6s 순단 → 0", svgChart("배포 중 5xx/s (전/후)", "err/s", listOf(0, 0, 14, 12, 9, 0, 0, 0), "#10b981")),
                ),
            ))
            add(RichSeed(
                title = "환불 동시 요청 시 예약 초과 (Repeatable Read 스냅샷)",
                project = "core-api", status = ErrorCaseStatus.OPEN, severityCode = 1, daysAgo = 6, hoursAgo = 8,
                tags = listOf("concurrency", "mysql", "isolation", "refund"),
                exceptionClass = null, exceptionMessage = null,
                stackTrace = """
# 동시 환불 2건 -> 가용액 초과 예약
payment balance=10000
req A: read balance(비잠금)=10000 -> reserve 8000
req B: read balance(비잠금)=10000 -> reserve 8000   (RR 스냅샷 고정: A 를 못 봄)
committed reserved=16000 > 10000
""",
                description = """
### 증상
동시 환불 요청이 가용액을 초과해 예약된다. MySQL 기본 격리 **REPEATABLE READ** 에서 비잠금 read 가 트랜잭션 시작 스냅샷에 고정되어, 서로의 in-flight 예약을 보지 못한다.

### 재현
```sql
-- tx A, tx B 동시
START TRANSACTION;
SELECT balance FROM payment WHERE id=1;      -- 둘 다 10000 (스냅샷)
-- 각자 8000 예약 -> 합 16000 > 10000
COMMIT;
```
""",
                steps = listOf(
                    SeedStep("RR 스냅샷 격리 확인", StepStatus.RESOLVED, "INVESTIGATION", """
비잠금 `SELECT` 는 MVCC 스냅샷을 읽어 **다른 트랜잭션의 미커밋/진행 중 변경을 못 본다.** in-flight 합산 가드가 새는 근본 원인.
"""),
                    SeedStep("잠긴 행으로 예약 상태 이전(설계)", StepStatus.IN_PROGRESS, "CODE_CHANGE", """
```sql
-- 행을 잠그고(최신값 강제) 조건부 증분
SELECT reserved_refund FROM payment WHERE id=1 FOR UPDATE;
UPDATE payment
   SET reserved_refund = reserved_refund + :amount
 WHERE id = 1 AND reserved_refund + :amount <= balance;  -- 0 rows = 초과
```
prod DDL(`reserved_refund` 컬럼 추가) 필요 — 적용 대기 중.
""", insight = "'읽고 나서 쓰기' 사이 갭은 비잠금 read 로 못 막는다 — 합산을 잠긴 행 상태로 옮겨야 한다."),
                ),
                solutionTitle = null,
                attachments = emptyList(),
            ))
            add(RichSeed(
                title = "클라이언트 4xx 가 전부 500 으로 매핑됨",
                project = "core-api", status = ErrorCaseStatus.RESOLVED, severityCode = 3, daysAgo = 24, hoursAgo = 2,
                tags = listOf("error-handling", "problemdetail", "validation"),
                exceptionClass = "org.springframework.web.bind.MethodArgumentNotValidException",
                exceptionMessage = "Validation failed for argument [0]",
                stackTrace = """
org.springframework.web.bind.MethodArgumentNotValidException: Validation failed for argument [0]
  -> field 'title': must not be blank
(핸들러 미등록 -> DefaultHandlerExceptionResolver -> 500)
""",
                description = """
### 증상
검증 실패/권한 오류가 `400/403` 이 아니라 **전부 500** 으로 내려가 FE 가 사용자 메시지를 구분 못 함.

### 기대 vs 실제
```text
POST /error-cases { title: "" }
expected: 400  { code: VALIDATION_ERROR, field: title }
actual:   500  { title: "Internal Server Error" }
```
""",
                steps = listOf(
                    SeedStep("예외 → 상태코드 매핑 공백 확인", StepStatus.RESOLVED, "INVESTIGATION", """
전역 예외 핸들러가 없어 검증 예외가 기본 resolver 로 빠져 500. ProblemDetail 표준 응답 부재.
"""),
                    SeedStep("shared-error GlobalExceptionHandler 도입", StepStatus.RESOLVED, "CODE_CHANGE", """
```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onInvalid(e: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.fieldErrorsAsMap())

    @ExceptionHandler(WorkspaceAccessDeniedException::class)
    fun onDenied(e: Exception): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "FORBIDDEN", e.message)
}
```
4xx/5xx 가 `traceId/code/type` 포함 일관 ProblemDetail 로 통일.
""", insight = "도메인/검증 예외 → HTTP 상태 매핑을 한 곳(RestControllerAdvice)에 모아야 FE 가 분기 가능."),
                ),
                solutionTitle = "RestControllerAdvice 로 4xx/5xx ProblemDetail 일관화",
                attachments = listOf(
                    SeedAtt(Att.JSON, "problemdetail-400.json", "개선 후 400 응답", "ProblemDetail 표준", """{
  "type": "https://error-archive.io/errors/validation",
  "title": "Bad Request",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "errors": [{ "field": "title", "reason": "must not be blank" }],
  "traceId": "9b2a4f1c-..."
}"""),
                ),
            ))
        }
    }
}
