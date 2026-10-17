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
        private const val SEED_VERSION = 8
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
            return """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" font-family="ui-sans-serif,system-ui,sans-serif"><rect width="$w" height="$h" rx="14" fill="#ffffff" stroke="#e5e7eb"/><text x="$padL" y="30" font-size="16" font-weight="700" fill="#111827">$title</text><text x="${w - padR}" y="30" font-size="12" fill="#9ca3af" text-anchor="end">$unit</text>$grid<polyline points="$pts" fill="none" stroke="$accent" stroke-width="2.5" stroke-linejoin="round"/><circle cx="%.1f" cy="%.1f" r="4.5" fill="$accent"/></svg>""".format(lastX, lastY)
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
            return """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h"><rect width="$w" height="$h" rx="12" fill="#0b0f17"/><rect width="$w" height="34" rx="12" fill="#161b26"/><circle cx="20" cy="17" r="5" fill="#ef4444"/><circle cx="40" cy="17" r="5" fill="#f59e0b"/><circle cx="60" cy="17" r="5" fill="#22c55e"/><text x="${w - 14}" y="21" font-size="12" fill="#6b7280" text-anchor="end" font-family="ui-monospace,monospace">$title</text>$body</svg>"""
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
### 요약
GitHub OAuth 로그인 **직후 홈 대시보드 진입**에서 간헐적으로 `500 INTERNAL_ERROR`. 서버 로그 최상단은 `ProfileHydrator` 의 `NullPointerException`. **아바타를 설정하지 않은 신규 계정**에서만 재현된다.

### 환경
- `core-api` (Kotlin · Spring Boot 4) → `iam-api` 프로필 조회 연동
- 재현 빈도: 신규 가입자 **약 100%** / 기존 사용자 0%
- 최초 인입: 온보딩 퍼널 1스텝 이탈률 급증 알림

### 영향 범위
- 신규 가입 → 첫 홈 진입이 깨져 **온보딩 초입에서 이탈**
- 홈 대시보드 위젯(최근 활동·추천)이 통째로 미표시
- 기존 사용자/다른 화면은 정상 → 체감 사용자는 한정적이나 **신규 유입에 직격**

### 증상 로그
```text
2026-10-16 14:02:11 ERROR HomeController - 500 GET /api/v1/users/me/recent-active-cases
java.lang.NullPointerException: Cannot invoke "String.length()" because "avatarUrl" is null
    at ProfileHydrator.hydrate(ProfileHydrator.kt:48)
    at HomeDashboardService.recentActive(HomeDashboardService.kt:72)
```

### 원인 (요약)
프로필 하이드레이션이 iam 응답의 `avatarUrl` 을 **non-null 로 단정**하고 `.length` 로 이니셜을 계산한다. 아바타 미설정 계정은 `avatarUrl: null` 로 내려오므로 그 지점에서 NPE. 외부 응답 필드를 nullable 로 다루지 않은 전형적인 **경계(boundary) 버그**.

### 조치
- **임시 완화**: 없음 — 영향이 신규 가입 전원이라 우회 없이 핫픽스 롤포워드.
- **영구 조치**: `avatarUrl` 을 nullable 로 받아 기본 아바타로 폴백 + 널 케이스 회귀 테스트 추가.

> 상세 재현/수정 과정은 아래 **타임라인**, 재현 테스트는 첨부 스니펫 참고.
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
### 요약
매일 **트래픽 피크(20:00 전후)**마다 `GET /api/v1/error-cases` 가 `503` 으로 무더기 실패하고 30초 뒤 타임아웃. HikariCP 커넥션 풀이 고갈되는데, **쿼리 자체는 12ms 로 빠르다** — 즉 쿼리가 느린 게 아니라 **커넥션을 오래 쥐고 있는 것**이 문제.

### 환경
- `core-api` · HikariCP(max-pool-size **10**) · PostgreSQL
- 재현: 동시 사용자 급증 시점(피크)에만. 평시 재현 안 됨
- 최초 인입: 피크 시간대 5xx 급증 + p99 폭증 알림

### 영향 범위
- 피크 10~15분간 케이스 목록/상세 **대량 503**
- 커넥션 대기열(pending 37)에 밀린 요청들이 30초 타임아웃까지 스레드 점유 → 연쇄 지연
- 피크 지나면 자연 회복 → 원인 추적이 까다로움

### 핵심 지표 (피크 구간)
```text
hikaricp_connections_active     10 / 10    (max 고갈)
hikaricp_connections_pending    37         (대기 폭증)
hikaricp_connections_timeout    25/min     (30s 초과)
avg query time                  12 ms      (쿼리는 빠름!)
```

### 원인 분석
`spring.jpa.open-in-view` 기본값 **true** 로, 커넥션이 서비스 메서드가 아니라 **요청 전체(뷰 렌더 포함) 생명주기** 동안 유지된다. 쿼리는 12ms 에 끝나도 커넥션 반납이 응답 완료까지 지연 → 피크에 10개 풀이 금세 소진. 여기에 작성자 조회 **N+1** 이 겹쳐 커넥션 점유 시간을 더 늘렸다.

### 조치
- **임시 완화**: 풀 크기 20→40 상향 시도 → 피크에 동일 고갈(보유 시간이 근본 원인이라 무의미했음).
- **영구 조치**: `open-in-view=false` + 작성자 조회 N+1 → batch, 풀 크기 재튜닝. 커넥션 보유 시간을 줄여 피크를 견디게 함.

> 지표 추이는 첨부 차트, 재발 방지 알럿은 댓글 참고. 상세 과정은 아래 타임라인.
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
### 요약
이미지가 많은 공개 아티클을 **PDF 로 export** 할 때 컨테이너(1 vCPU / 512MB)가 힙을 소진해 `OutOfMemoryError: Java heap space`. 동시 2건이 겹치면 즉사한다. 아직 **IN PROGRESS** — 완화책은 적용했지만 근본 조치 진행 중.

### 환경
- `publish-api` · OpenHTMLToPdf(PDFBox) 렌더 · JVM 25 / 컨테이너 512MB
- 재현: 이미지 10장 이상 대형 아티클 PDF export, 특히 동시 요청 시
- 최초 인입: 공개 PDF 다운로드 실패 제보 + 파드 OOMKilled 재시작

### 영향 범위
- 대형 아티클 PDF export 실패(그 외 export 는 정상)
- OOM 시 파드 재시작 → 그 순간 **다른 요청들도 순단**
- 공개 페이지 열람 자체는 영향 없음(렌더 경로만)

### 힙 덤프 요약
```text
BufferedImage             357 MB   (70.1%)   <- 원본 해상도 이미지를 통째로 로드
byte[] (PDFBox COSStream)  92 MB   (18.0%)
그 외                       63 MB
---
peak heap ~ 512 MB (limit)  → OutOfMemoryError
```

### 원인 분석
PDF 렌더가 **원본 해상도 이미지를 BufferedImage 로 통째 로드**해 힙의 70% 를 차지한다. 512MB 컨테이너에서 대형 문서 1건도 아슬아슬한데, 동시 2건이면 즉시 초과. 렌더 동시성 제한도 없어 피크에 겹치면 바로 OOM.

### 조치
- **임시 완화(적용)**: export 전용 세마포어(permit=1)로 렌더 **직렬화** → 동시성발 OOM 빈도 급감. 단 대형 문서 1건은 여전히 위험.
- **영구 조치(진행 중)**: 업로드 시 **렌더용 다운스케일 사본**(장변 2000px) 생성 → 렌더 입력 크기 상한을 둬 메모리 예측 가능하게.

> 힙 추이는 첨부 차트. 진행 상황은 아래 타임라인.
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
### 요약
한정 수량(**1000**) 쿠폰을 **2개 인스턴스**가 공유 DB 에서 발급할 때 실제 발급이 **1125건**(125 초과)이 됐다. 재고 확인(check)과 차감(update) 사이에 원자성/직렬화가 없어 생기는 **분산 동시성(race)** 문제. 현재 **IN PROGRESS** — 락은 넣었고 장애 대비 이중화 설계 중.

### 환경
- `core-api` · 공유 MySQL · 앱 인스턴스 **2대**(수평확장)
- 재현: k6 부하(VU 100)로 2대 동시 발급 시. **단일 인스턴스에선 재현 안 됨**
- 최초 인입: 이벤트 종료 후 쿠폰 발급 수가 한도를 초과한 정산 불일치 제보

### 영향 범위
- 한정 수량 초과 발급 → **정산/비용 불일치**, 재고 신뢰도 하락
- 단일 인스턴스 환경(로컬/테스트)에선 안 보여 **운영 수평확장에서만 발현**

### 레이스 타임라인
```text
T1  A: SELECT issued (=990)  < 1000   -> OK
T2  B: SELECT issued (=990)  < 1000   -> OK      (A 의 증분을 아직 못 봄)
T3  A: UPDATE issued = 991
T4  B: UPDATE issued = 991                        <- 둘 다 통과 → 초과 발급
```

### 원인 분석
"읽고(check) 나서 쓰는(update)" 사이 구간이 **인스턴스 간 직렬화되지 않는다.** 비잠금 read 는 다른 인스턴스의 in-flight 증분을 보지 못하므로, 두 인스턴스가 같은 잔여수량을 보고 동시에 발급을 승인한다.

### 조치
- **1차(적용)**: 쿠폰 코드 키로 **Redisson 분산 락** → 임계구역 직렬화. 초과는 사라졌으나 Redis 가 **단일 장애점**이 됨.
- **2차(진행 중)**: 정확성의 최종 보루를 DB 로 — `UPDATE ... WHERE issued < limit` **조건부 UPDATE** + `UNIQUE(coupon_id, user_id)`. Redis 불가 시 이 경로로 자동 전환(락=성능, 제약=정합성 이중화).

> 부하 결과는 첨부 JSON, 초과 추이는 차트. 상세는 아래 타임라인.
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
### 요약
실시간 알림 `EventSource`(SSE) 연결이 약 **30초마다 끊겼다 재연결**을 반복한다. 기능은 동작하지만 재연결마다 순간 누락/깜빡임이 생기고 불필요한 연결 비용이 든다. 원인은 **heartbeat 주기(45s)가 프록시 idle timeout(30s)보다 길어서**.

### 환경
- `noti-api` SSE(`text/event-stream`) · 앞단 프록시(Nginx) idle timeout **30s**
- 서버 heartbeat(keep-alive 코멘트) 주기 **45s**
- 재현: 알림 페이지를 열어두고 ~30초 무이벤트 구간

### 영향 범위
- 알림 스트림이 30초마다 재연결 → **순간적 이벤트 누락/지연** 가능
- 서버/프록시에 불필요한 재연결 부하
- 치명적이진 않으나 "실시간" 체감 품질 저하

### 증상 (브라우저 네트워크)
```text
200 text/event-stream  (30.1s)  -> closed → reconnect
200 text/event-stream  (29.9s)  -> closed → reconnect
```

### 원인 분석
SSE 는 이벤트가 없어도 연결을 살리려 주기적 **heartbeat(주석 프레임)** 를 보낸다. 그런데 그 주기(45s)가 경로상 **가장 짧은 idle timeout(프록시 30s)** 보다 길어서, 트래픽 없는 30초 구간에 프록시가 연결을 먼저 끊어버린다 → 클라이언트가 즉시 재연결.

### 조치
- **영구 조치**: heartbeat 주기를 **15s**(프록시 idle timeout 아래)로 단축. 이후 재연결 사라짐.
- 원칙: `heartbeat 주기 < 경로상 최소 idle timeout` 을 항상 만족시킨다.

> 재연결 로그는 첨부, 상세는 아래 타임라인.
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
### 요약
알림을 **생성한 노드**와 사용자가 **SSE 로 접속한 노드**가 다르면 실시간 알림이 **전달되지 않는다**. `SseEmitter` 레지스트리가 노드-로컬이라, A 노드에서 만든 이벤트가 B 노드에 붙은 사용자에게 닿지 못한다 — 수평확장(2대 이상)에서만 발현되는 상태 공유 문제.

### 환경
- `noti-api` **2 노드** · 사용자별 `SseEmitter` 는 **접속한 노드에만** 등록
- 재현: 노드 2대 이상 + 알림 생성 노드 ≠ 사용자 접속 노드
- 단일 노드에선 항상 정상(그래서 스테이징 1대에선 안 보였음)

### 영향 범위
- 알림이 **무작위로 누락**(사용자가 어느 노드에 붙었는지에 따라)
- 수평확장할수록 미전달 확률 증가 → 실시간 알림 신뢰도 붕괴

### 증상 (관찰)
```text
A-node: createNotification(userId=42) -> local emitters[42] = []   (user 42 는 B-node 접속)
B-node: emitters[42] = 1               but never receives
redis PUBSUB NUMSUB noti:realtime -> 0  (아무도 구독 안 함 → 교차 전달 불가)
```

### 원인 분석
알림 전달이 **노드 로컬 메모리(emitter 레지스트리)**에만 의존한다. 생성 이벤트가 다른 노드로 전파될 경로가 없어, 생성 노드 ≠ 접속 노드이면 그대로 유실. "인메모리라 1대에서만 맞는" 전형적인 수평확장 상태 공유 결함.

### 조치
- **영구 조치**: 노드-로컬 emitter 는 그대로 두고, **알림 생성 트리거만 Redis pub/sub(`noti:realtime`) 로 브로드캐스트**. 모든 노드가 구독해 자기 로컬 emitter 로 전달 → 어느 노드에서 생성해도 전 노드 접속자에게 도달.
- 검증: `PUBSUB NUMSUB == 노드 수`, publish→구독→로컬 전달 바이트 동일 통합테스트.

> 구독자 확인 스샷은 첨부, 상세는 아래 타임라인.
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
### 요약
추천 팔로우 조회(`/users/me/suggested-followees`)가 **cold-start(팔로잉 0)** 구간에서 p95 **7.4초**. 신규 사용자 홈이 체감상 멈춘다. 원인은 인덱스 없는 **full-scan fallback + 작성자 N+1** 이 겹친 것. 조치 후 **p95 7,450ms → 89ms**.

### 환경
- `core-api` 추천 로직 · PostgreSQL · 작성자 프로필은 iam-api 조회
- 재현: **팔로잉이 없는 신규 사용자**(cold-start fallback 경로)
- 최초 인입: 신규 홈 로딩 지연 제보 + 추천 API p95 알림

### 영향 범위
- 신규 사용자 홈 첫 로딩이 **7초+** → 사실상 멈춘 것처럼 보임
- 추천 호출이 DB 를 풀스캔 → 피크에 **DB 부하 전파**
- 기존(팔로잉 있는) 사용자는 빠름 → cold-start 한정

### 병목 분해 (APM 트레이스)
```text
total p95                 7,450 ms
├─ full-scan fallback      6,900 ms  (92%)   <- ORDER BY random(), 인덱스 없음
└─ author hydration N+1      520 ms          <- 후보 수만큼 iam 프로필 개별 호출
```

### 원인 분석
팔로잉이 없으면 "인기/랜덤 공개 작성자" **fallback** 으로 빠지는데, 그 쿼리가 `ORDER BY random()` + 인덱스 부재로 **전체 테이블 스캔**(142k rows)을 탄다(6.9s). 거기에 후보 작성자 프로필을 **건별로 iam 호출(N+1)** 해 0.5s 추가. cold-start 경로에서 "느린 fallback + N+1"이 **중첩**되며 최악 p95 를 만든 전형적 패턴.

### 조치
- **영구 조치 (p95 7,450→89ms)**:
  1. fallback 쿼리에 `created_at` **부분 인덱스** + 최근 30일 윈도우 + `LIMIT` → full-scan 제거.
  2. 작성자 프로필 조회를 **1회 batch** 로 병합(N+1 제거).
- 교훈: cold-start 는 느린 경로일수록 N+1 과 겹치기 쉬워, **둘을 함께** 잡아야 효과가 난다.

> before/after 실행계획은 첨부 스니펫, p95 추이는 차트. 상세는 아래 타임라인.
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
### 요약
새 환경에서 앱 부팅이 `ddl-auto=validate` 단계에서 **"없는 테이블" 스키마 검증 실패**로 죽는다. 그런데 Flyway 의존성(`flyway-core`)은 분명히 들어있다. 범인은 **Spring Boot 4.0 의 autoconfig 모듈 분리** — 라이브러리만으론 마이그레이션이 자동 실행되지 않는다.

### 환경
- `iam-api` · **Spring Boot 4.0** · PostgreSQL · Flyway 마이그레이션 + `ddl-auto=validate`
- 재현: **깨끗한 DB** 로 첫 부팅(마이그레이션이 한 번도 안 돈 상태)
- 최초 인입: 신규 스테이징 구성 중 부팅 CrashLoop

### 영향 범위
- 신규/초기화 환경에서 **앱 부팅 자체 실패** → 배포 불가
- 기존(이미 스키마가 있는) 환경은 우연히 통과 → 문제를 늦게 발견

### 단서
```text
org.hibernate.tool.schema.spi.SchemaManagementException:
  Schema-validation: missing table [iam_refresh_token]
```
```sql
-- flyway_schema_history 테이블 자체가 없음 → migrate 가 한 번도 안 돌았다
SELECT * FROM flyway_schema_history;   -- ERROR: relation does not exist
```

### 원인 분석
Spring Boot **4.0** 은 자동설정을 모듈로 쪼갰다. `flyway-core` 라이브러리만 넣으면 **`FlywayAutoConfiguration` 이 클래스패스에 붙지 않아** 부팅 시 `migrate` 가 돌지 않는다. 그 결과 스키마가 비어있고, 뒤이은 `ddl-auto=validate` 가 "테이블 없음"으로 실패. 라이브러리는 있는데 자동설정이 빠진 **Boot 4 특유의 함정**.

### 조치
- **영구 조치**: `org.springframework.boot:spring-boot-flyway`(autoconfig 모듈)를 의존성에 추가 → 부팅 로그에 `Flyway ... migrating schema` 등장, 스키마 생성 확인.
- 적용 범위: DB 를 쓰는 모든 서비스 공통 점검.

> 상세 확인 과정은 아래 타임라인.
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
### 요약
첨부 업로드 `putObject` 가 **OCI Object Storage 에서만 403**(`required information to complete authentication...`). MinIO(local)·AWS 에선 성공해서 코드가 아니라 **S3 공급자 호환성** 문제로 좁혀졌다. 원인은 AWS SDK v2 의 기본 **aws-chunked 스트리밍 서명**을 OCI S3 호환 API 가 미지원.

### 환경
- `core-api` 첨부 스토리지 · AWS SDK v2(S3) · prod=**OCI Object Storage(S3 호환)**, local=MinIO
- 재현: prod(OCI) 에서 이미지/파일 업로드 시 100%
- 최초 인입: prod 첨부 업로드 전면 실패 제보

### 영향 범위
- prod 에서 **모든 첨부 업로드 불가**(이미지/로그 첨부 기능 마비)
- 조회/다운로드(presigned GET)는 정상 → 업로드(put) 경로 한정
- local·AWS 에선 재현 안 돼 환경 차이 추적이 필요했음

### 요청 비교
```http
PUT /error-archive-attachments/attachments/f0/f0c12ab9
x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD   <- aws-chunked
Content-Encoding: aws-chunked
< HTTP/1.1 403 Forbidden
< body: There is a required information to complete authentication that was not provided.
```

### 원인 분석
AWS SDK v2 는 `putObject` 를 기본적으로 **aws-chunked content-encoding + streaming 서명**으로 보낸다. AWS·MinIO 는 이를 처리하지만 **OCI 의 S3 호환 API 는 미지원**이라 서명 검증에 실패해 403. "S3 호환"이라도 서명/인코딩 세부에서 공급자마다 갈린다는 점이 핵심.

### 조치
- **영구 조치**: S3 클라이언트에 **`chunkedEncodingEnabled(false)`**(+ path-style) 설정 → OCI `putObject` 정상. (iam 아바타, core 첨부 모두 동일 적용.)
- 교훈: S3 "호환" 스토리지는 chunked encoding·path-style 등 세부 호환부터 의심.

> 403 응답 로그는 첨부, 상세는 아래 타임라인.
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
### 요약
배포할 때마다 **약 6초간 502/503 순단**이 발생한다. 배포 전략이 `Recreate` 라 **구 파드를 먼저 내리고** 새 파드를 띄우는 공백이 생기기 때문. RollingUpdate + readiness 게이트 + preStop 드레인으로 **순단 0초** 달성.

### 환경
- k8s 배포(Deployment) · `strategy.type: Recreate`
- 재현: 모든 배포(롤아웃)마다
- 최초 인입: 배포 중 5xx 스파이크 + 사용자 "방금 잠깐 안 됐다" 제보

### 영향 범위
- 배포 때마다 **6초 downtime** → 무중단 배포 아님
- 하루 여러 번 배포 시 체감 안정성·SLA 저하
- in-flight 요청이 종료되는 파드에서 끊김

### 증상 (배포 중 1초 간격 프로브)
```text
200 200 502 503 503 503 503 200 200
            └──────── ~6초 순단 ────────┘
```
```yaml
spec:
  strategy:
    type: Recreate   # 구 파드를 먼저 내리고 새 파드를 띄움 → 공백 발생
```

### 원인 분석
`Recreate` 는 롤아웃 시 **기존 파드를 전부 종료한 뒤** 새 파드를 생성한다. 새 파드가 Ready 될 때까지(기동 ~6초) **엔드포인트가 비어** 502/503. 또 readiness 게이트/드레인이 없어 종료 중 파드로도 트래픽이 흘러 끊김이 더 커진다.

### 조치
- **영구 조치 (순단 0초)**:
  1. `RollingUpdate`(maxUnavailable **0**, maxSurge 1) — 새 파드가 Ready 된 뒤에만 구 파드 종료.
  2. `readinessProbe` 로 준비된 파드에만 트래픽 라우팅.
  3. `preStop: sleep 5` 로 종료 전 in-flight 드레인(LB 에서 빠질 시간 확보).
- 결과: 배포 중 5xx **0건**.

> 전/후 5xx 추이는 첨부 차트, 상세는 아래 타임라인.
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
### 요약
동시 환불 요청이 결제 **가용액을 초과해 예약**된다(예: 잔액 10000 에 8000 예약 2건 → 16000). MySQL 기본 격리 **REPEATABLE READ** 에서 비잠금 `SELECT` 가 트랜잭션 시작 스냅샷에 고정돼, 서로의 in-flight 예약을 보지 못하기 때문. 현재 **OPEN** — 잠긴 행 기반 가드로 설계 중(prod DDL 필요).

### 환경
- `core-api` 환불 도메인 · MySQL(기본 격리 **REPEATABLE READ**)
- 재현: 같은 결제 건에 **동시 환불 요청 2건**
- 최초 인입: 환불 합계가 결제액을 넘긴 정합성 제보

### 영향 범위
- **초과 환불 예약** → 금전 정합성 리스크(실제 환불 과다 가능)
- 동시 요청이 겹칠 때만 → 재현/추적이 어려움
- 단건 순차 요청에선 안 보임

### 재현
```sql
-- tx A, tx B 동시 (RR)
START TRANSACTION;
SELECT reserved_refund, balance FROM payment WHERE id = 1;  -- 둘 다 같은 스냅샷(0 / 10000)
-- 각자 8000 예약 가능하다고 판단 → 합 16000 > 10000
COMMIT;   -- 둘 다 커밋 → 초과
```

### 원인 분석
"가용액을 읽고(check) → 예약을 더하는(update)" 사이, **비잠금 read 가 MVCC 스냅샷**(트랜잭션 시작 시점)으로 고정돼 다른 트랜잭션의 **미커밋/in-flight 예약을 보지 못한다.** 그래서 두 트랜잭션이 같은 가용액을 근거로 각각 예약을 승인한다. 애플리케이션 메모리 가드로는 못 막는 격리 수준 이슈.

### 조치
- **진행 중(설계)**: 합산 대상을 **잠긴 행의 상태**로 이동 — `SELECT ... FOR UPDATE` 로 Payment 행을 잠그고, `reserved_refund` 컬럼을 **조건부 증분**:
  `UPDATE payment SET reserved_refund = reserved_refund + :amt WHERE id=:id AND reserved_refund + :amt <= balance` (0 rows = 초과 거절).
- prod DDL(`reserved_refund` 컬럼 추가) 선행 필요 → 적용 대기.
- 교훈: '읽고 나서 쓰기' 갭은 비잠금 read 로 못 막는다 — 합산을 **잠긴 행**으로 옮겨야 한다.

> 상세 분석은 아래 타임라인.
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
### 요약
검증 실패/권한 오류 같은 **클라이언트 오류(4xx)가 전부 500 으로** 내려간다. FE 가 "입력 오류"와 "서버 장애"를 구분하지 못해 사용자에게 적절한 메시지를 못 보여주고, 알림/모니터링에서도 5xx 로 잡혀 노이즈가 된다.

### 환경
- `core-api` · Spring MVC · 전역 예외 핸들러(RestControllerAdvice) 부재
- 재현: 잘못된 입력(빈 title 등)·권한 없는 접근 요청
- 최초 인입: FE 에서 "왜 검증 오류인데 500 이 뜨냐" + 5xx 알림 과다

### 영향 범위
- FE 가 4xx 분기를 못 해 **사용자 친화 메시지 불가**(그냥 "서버 오류")
- 실제 정상적인 검증 실패가 **5xx 지표/알림을 오염** → 진짜 장애 묻힘
- traceId/code 없는 뭉뚱그린 응답으로 **지원/디버깅 난이도 상승**

### 기대 vs 실제
```text
POST /api/v1/error-cases  { "title": "" }
expected: 400  { "code": "VALIDATION_ERROR", "errors": [{ "field": "title", ... }] }
actual:   500  { "title": "Internal Server Error" }
```

### 원인 분석
전역 예외 핸들러가 없어, `MethodArgumentNotValidException`(검증)·권한 예외 등이 매핑되지 못하고 **기본 resolver 로 빠져 500**이 된다. 상태코드·에러코드·traceId 를 담은 표준(ProblemDetail) 응답 규약이 부재.

### 조치
- **영구 조치**: `shared-error` 의 `@RestControllerAdvice`(`GlobalExceptionHandler`) 도입 — 검증→400, 권한→403, 그 외→적절한 코드로 매핑하고 **ProblemDetail(type/title/status/code/traceId)** 로 통일.
- 교훈: 도메인/검증 예외 → HTTP 상태 매핑을 **한 곳**에 모아야 FE 분기와 모니터링 신뢰도가 산다.

> 개선 후 400 응답 예시는 첨부 JSON, 상세는 아래 타임라인.
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
