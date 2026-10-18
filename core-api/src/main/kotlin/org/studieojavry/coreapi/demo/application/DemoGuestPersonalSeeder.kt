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
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.AddCaseToWatchlistUseCase
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.domain.model.Comment
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.shared.util.FingerprintGenerator
import java.time.Instant
import java.time.LocalDateTime

/**
 * 게스트("로그인 없이 둘러보기") 전용 **개인(personal) 케이스** 지연 시더.
 *
 * 왜 지연(lazy)인가: 개인 케이스는 `workspaceId=null` + `PRIVATE` 라 **소유자 본인에게만** 보인다.
 * 데모봇이 미리 깔아둬도 다른 유저인 게스트에겐 안 보이므로, **각 게스트가 '개인/Public 홈' 피드를
 * 처음 조회하는 순간 그 게스트 소유로 생성**한다(로그인 시점 아님 → 홈을 안 보는 방문자에겐 생성 비용 0).
 *
 * 콘텐츠는 실제 공개 케이스(예: errorCaseId=4)와 동급의 "탐정 서사" — 간결하지만 불릿/인용구/임베드가 든
 * 본문 + 각 스텝마다 코드블록과 서사(실패한 시도·FAILED 포함) + 스텝별 insight + 솔루션(실제 통한 스텝만) +
 * 메트릭/터미널 첨부(@attach) + 핵심 코드 스니펫(@snippet).
 *
 * 효과: 생성 직후 recent-active-cases(소유자 기준)에 뜨고, 자동 watch 로 watchlist-feed 에도 즉시 노출.
 * 정리: 게스트는 1회용이라 개인 케이스가 쌓인다 → [DemoResetService] 야간 리셋이 `workspaceId IS NULL`
 * 케이스를 함께 purge(데모 환경에선 개인 케이스=게스트 산물).
 */
@Service
class DemoGuestPersonalSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val codeSnippetRepository: CodeSnippetRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val storagePort: AttachmentStoragePort,
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val addCaseToWatchlistUseCase: AddCaseToWatchlistUseCase,
    private val cacheInvalidator: org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 게스트가 홈 피드를 열 때 호출(컨트롤러에서 guest 일 때만 + `runCatching` 으로 감싸 실패해도 피드 조회를
     * 막지 않음). 단일 `@Transactional` 진입점 — 외부(컨트롤러)에서 호출돼야 프록시 트랜잭션이 적용된다
     * (self-invocation 금지). 예외는 **삼키지 않고** 전파해 부분 생성 시 전체 롤백시킨다(멱등하므로 재시도).
     *
     * 멱등: 이 유저가 이미 케이스를 보유하면 skip. `demo.enabled=false` 면 no-op.
     */
    @Transactional
    fun ensureSeededForGuest(userId: Long) {
        if (!props.enabled) return
        val already = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = null, ownerUserId = userId, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = 1,
            ),
        ).isNotEmpty()
        if (already) return

        SEED.forEach { seedOne(it, userId) }
        // 생성 전에 빈 결과가 캐시됐을 수 있으니 두 홈 위젯 캐시를 무효화해 즉시 반영.
        // (DashboardCacheInvalidator 는 내부에서 예외를 삼키므로 tx 를 오염시키지 않는다.)
        cacheInvalidator.evictBoth(userId)
        log.info { "guest personal cases seeded (userId=$userId, n=${SEED.size})" }
    }

    private fun seedOne(s: PersonalSeed, ownerUserId: Long) {
        val snippets = s.snippets.mapNotNull { buildSnippet(it, ownerUserId) }
        val attachments = if (props.seedAttachments) s.attachments.mapNotNull { buildAttachment(it, ownerUserId) } else emptyList()

        val raw = RawStackTrace(s.stackTrace.trim())
        val snapshot = ErrorSnapshot(
            rawPaste = s.stackTrace.trim(),
            exceptionClass = s.exceptionClass,
            exceptionMessage = s.exceptionMessage,
            rawStackTrace = raw,
            fingeprint = FingerprintGenerator.generate(s.exceptionClass, raw),
        )
        val meta = Meta.create(workspaceId = null, severityCode = s.severityCode)
        val occurredAt = LocalDateTime.now().minusDays(s.daysAgo).minusHours(s.hoursAgo)

        // 본문에 스니펫/첨부 인라인 임베드(@snippet/@attach) — 렌더러가 해당 위치에 코드/이미지/파일을 끼워 넣는다.
        val description = s.description + buildString {
            snippets.forEach { append("\n\n@snippet(").append(it.markerId).append(")") }
            attachments.forEach { append("\n\n@attach(").append(it.markerId).append(")") }
        }

        val case = ErrorCase.create(
            ownerUserId = ownerUserId,
            title = s.title,
            project = s.project,
            snapshot = snapshot,
            description = description,
            meta = meta,
            visibility = Visibility.PRIVATE, // 개인 케이스 — 소유자 본인만 read.
            snippets = snippets,
            attachments = attachments,
            tags = emptyList(),
            occurredAt = occurredAt,
        )
        applyStatus(case, s.status, ownerUserId)
        val caseId = errorCaseRepository.save(case).id!!

        (s.tags + GUEST_PERSONAL_TAG).mapNotNull { ErrorCase.normalizeTag(it) }.distinct()
            .forEach { tagRepository.add(caseId, it) }

        val stepIds = s.steps.mapIndexed { i, st ->
            stepRepository.save(
                Step.create(
                    errorCaseId = caseId, authorUserId = ownerUserId, orderIndex = i,
                    title = st.title, status = st.status, attemptType = st.attemptType,
                    body = st.body, insight = st.insight,
                ),
            ).id!!
        }
        // 솔루션 = 실제로 통한 스텝(status=RESOLVED, 조사/실패 시도 제외) — 솔루션 선택 시 나머지는 dim 되어
        // "이 스텝들이 진짜 해결책"이 드러난다(실패한 시도·조사는 흐려짐).
        if (s.solutionTitle != null) {
            val solutionStepIds = s.steps.zip(stepIds)
                .filter { (st, _) -> st.status == StepStatus.RESOLVED && st.attemptType != "INVESTIGATION" }
                .map { it.second }
                .ifEmpty { stepIds }
            if (solutionStepIds.isNotEmpty()) {
                solutionRepository.save(Solution.create(caseId, ownerUserId, s.solutionTitle, solutionStepIds))
            }
        }
        s.comments.forEach { c ->
            val top = commentRepository.save(
                Comment.create(caseId, ownerUserId, null, c.body, QuoteSourceKind.NONE, null, null),
            )
            c.reply?.let {
                commentRepository.save(
                    Comment.create(caseId, ownerUserId, top.id, it, QuoteSourceKind.NONE, null, null),
                )
            }
        }

        // createdAt 백데이트 — 날짜 자연스럽게 분산.
        val resolved = s.status == ErrorCaseStatus.RESOLVED
        errorCaseRepository.update(
            ErrorCase.reconstitute(
                id = caseId, ownerUserId = ownerUserId, title = s.title, project = s.project,
                snapshot = snapshot, description = description, meta = meta, visibility = Visibility.PRIVATE,
                snippets = emptyList(), attachments = emptyList(), tags = emptyList(),
                status = s.status, occurredAt = occurredAt, createdAt = occurredAt, updatedAt = occurredAt,
                resolvedAt = if (resolved) occurredAt else null,
                resolvedByUserId = if (resolved) ownerUserId else null,
            ),
        )

        // 자동 watch — Public/개인 홈의 watchlist-feed 섹션에도 즉시 노출(owner 는 PRIVATE read 가능).
        // 같은 트랜잭션에 참여하므로 여기서 catch 하지 않는다(실패 시 전체 시드 롤백 → 다음 호출 재시도).
        addCaseToWatchlistUseCase.invoke(caseId, ownerUserId)
    }

    private fun buildSnippet(sn: PersonalSnippet, ownerUserId: Long): CodeSnippet? = runCatching {
        codeSnippetRepository.save(
            CodeSnippet(
                markerId = markerIdGenerator.generateSnippetMarkerId(),
                title = sn.title, language = sn.language, filePathOrClass = sn.file, lineRange = sn.lineRange,
                caption = sn.caption, code = sn.code, uploadedByUserId = ownerUserId, uploadedAt = Instant.now(),
            ),
        )
    }.getOrElse { log.warn { "guest personal snippet skipped (${sn.title}): ${it.message}" }; null }

    private fun buildAttachment(a: PersonalAtt, ownerUserId: Long): Attachment? {
        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = "attachments/${markerId.take(2).ifBlank { "00" }}/$markerId"
        val bytes = a.content.toByteArray(Charsets.UTF_8)
        return runCatching {
            storagePort.put(objectKey, bytes, a.contentType)
            attachmentRepository.save(
                Attachment(
                    markerId = markerId, title = a.title, caption = a.caption, fileName = a.fileName,
                    contentType = a.contentType, size = bytes.size.toLong(), kind = a.kind,
                    objectKey = objectKey, uploadedByUserId = ownerUserId, uploadedAt = Instant.now(),
                ),
            )
        }.getOrElse { log.warn { "guest personal attachment skipped (${a.fileName}): ${it.message}" }; null }
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

    private enum class Att { CHART, TERMINAL, JSON, LOG }
    private class PersonalAtt(type: Att, val fileName: String, val title: String, val caption: String?, val content: String) {
        val contentType = when (type) { Att.CHART, Att.TERMINAL -> "image/svg+xml"; Att.JSON -> "application/json"; Att.LOG -> "text/plain" }
        val kind = when (type) { Att.CHART, Att.TERMINAL -> AttachmentKind.IMAGE; Att.JSON -> AttachmentKind.JSON; Att.LOG -> AttachmentKind.TEXT }
    }
    private class PersonalStep(val title: String, val status: StepStatus, val attemptType: String, val body: String, val insight: String? = null)
    private class PersonalComment(val body: String, val reply: String? = null)
    private class PersonalSnippet(
        val title: String, val language: String, val code: String,
        val file: String? = null, val lineRange: String? = null, val caption: String? = null,
    )
    private class PersonalSeed(
        val title: String, val project: String, val status: ErrorCaseStatus, val severityCode: Int?,
        val daysAgo: Long, val hoursAgo: Long, val tags: List<String>,
        val exceptionClass: String?, val exceptionMessage: String?, val stackTrace: String,
        val description: String, val steps: List<PersonalStep>, val solutionTitle: String?,
        val attachments: List<PersonalAtt> = emptyList(),
        val comments: List<PersonalComment> = emptyList(),
        val snippets: List<PersonalSnippet> = emptyList(),
    )

    companion object {
        /** 게스트 개인 케이스 식별 태그 — 야간 리셋/정리에서 이 산물을 구분하는 용도. */
        const val GUEST_PERSONAL_TAG = "demo-guest-personal"

        private fun svgChart(title: String, unit: String, values: List<Int>, accent: String = "#6366f1"): String {
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
            val body = lines.mapIndexed { i, rawLine ->
                val color = when {
                    rawLine.contains("ERROR") || rawLine.contains("Exception") -> "#f87171"
                    rawLine.trimStart().startsWith("at ") -> "#9ca3af"
                    rawLine.contains("WARN") -> "#fbbf24"
                    rawLine.trimStart().startsWith("+") -> "#34d399"
                    rawLine.trimStart().startsWith("-") -> "#f87171"
                    else -> "#d1d5db"
                }
                val safe = rawLine.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                """<text x="18" y="${top + i * lineH}" font-size="12.5" font-family="ui-monospace,Menlo,monospace" fill="$color">$safe</text>"""
            }.joinToString("\n")
            return """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h"><rect width="$w" height="$h" rx="12" fill="#0b0f17"/><rect width="$w" height="34" rx="12" fill="#161b26"/><circle cx="20" cy="17" r="5" fill="#ef4444"/><circle cx="40" cy="17" r="5" fill="#f59e0b"/><circle cx="60" cy="17" r="5" fill="#22c55e"/><text x="${w - 14}" y="21" font-size="12" fill="#6b7280" text-anchor="end" font-family="ui-monospace,monospace">$title</text>$body</svg>"""
        }

        private val SEED: List<PersonalSeed> = buildList {
            // ── 1) Testcontainers 포트 충돌 (RESOLVED) ──────────────────────────────
            add(
                PersonalSeed(
                    title = "CI는 통과하는데 로컬에서만 터지는 Testcontainers 포트 충돌 — 좀비 컨테이너 추적기",
                    project = "side-project/api",
                    status = ErrorCaseStatus.RESOLVED,
                    severityCode = 3,
                    daysAgo = 2, hoursAgo = 3,
                    tags = listOf("testcontainers", "ci", "flaky", "docker", "postgres"),
                    exceptionClass = "org.testcontainers.containers.ContainerLaunchException",
                    exceptionMessage = "Could not create/start container: port is already allocated",
                    stackTrace = """
org.testcontainers.containers.ContainerLaunchException: Could not create/start container
    at org.testcontainers.containers.GenericContainer.tryStart(GenericContainer.java:563)
    at org.testcontainers.containers.GenericContainer.lambda${'$'}doStart${'$'}0(GenericContainer.java:344)
    at org.testcontainers.containers.GenericContainer.doStart(GenericContainer.java:334)
Caused by: com.github.dockerjava.api.exception.InternalServerErrorException: Status 500:
    {"message":"Bind for 0.0.0.0:5432 failed: port is already allocated"}
""",
                    description = """
CI(GitHub Actions)에선 **100% 통과**하는 통합테스트가 로컬에서만 가끔 `port is already allocated` 로 죽는다. 하루를 날리고서야 범인이 **좀비 컨테이너 + 고정 포트**임을 잡았다.

- 재현율: 로컬 약 **30%**(직전 런을 중단했을 때 급증) / CI **0%**
- 증상: `PostgreSQLContainer` 기동 단계에서 즉사, 앱 코드는 한 줄도 안 돎
- 겉보기엔 "플래키 테스트" 지만 실제론 **환경 상태 의존** 버그

> CI가 늘 깨끗한 러너라 안 터지니 문제가 가려졌다 — "내 머신에서만" 의 전형.
""",
                    steps = listOf(
                        PersonalStep(
                            "CI vs 로컬 차이부터 가른다", StepStatus.RESOLVED, "INVESTIGATION",
                            """
CI는 잡마다 새 러너라 잔재가 없고, 로컬은 이전 런 컨테이너가 남는다는 가설. 테스트 전 포트 점유부터 확인했다.
```bash
${'$'} docker ps --filter publish=5432
CONTAINER ID   IMAGE               STATUS        PORTS
a1b2c3d4e5f6   postgres:16-alpine  Up 2 hours    0.0.0.0:5432->5432/tcp   # ← 좀비
${'$'} lsof -i :5432
com.docker  931  user  TCP *:postgresql (LISTEN)
```
직전에 디버그로 중단한 런의 컨테이너가 그대로 5432를 물고 있었다.
""".trim(),
                            insight = "재현이 '환경 상태'에 의존하면 코드가 아니라 CI/로컬 차이부터 의심한다.",
                        ),
                        PersonalStep(
                            "고정 포트 매핑이 범인", StepStatus.RESOLVED, "INVESTIGATION",
                            """
테스트 설정을 보니 호스트 포트를 5432로 **못박아** 두고 있었다. 좀비 컨테이너나 병렬 런과 겹치면 바로 충돌.
```kotlin
@Container
val db = PostgreSQLContainer("postgres:16-alpine")
    .withCreateContainerCmdModifier { cmd ->
        cmd.hostConfig?.withPortBindings(
            PortBinding(Ports.Binding.bindPort(5432), ExposedPort(5432)) // ← 고정
        )
    }
```
""".trim(),
                        ),
                        PersonalStep(
                            "reuse=true 로 재사용 시도 — 실패", StepStatus.FAILED, "CONFIG",
                            """
"그럼 컨테이너를 재사용하면 되지" 싶어 reuse 를 켜봤다.
```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```
하지만 reuse 는 **고정 포트 충돌을 해결하지 못했고**(여전히 같은 포트를 요구), 오히려 테스트 간 상태 오염까지 생겨 더 나빠졌다. 되돌림.
""".trim(),
                            insight = "reuse 는 '기동 비용' 최적화지 '포트 충돌' 해법이 아니다 — 문제를 잘못 짚었다.",
                        ),
                        PersonalStep(
                            "고정 포트 제거 → 매핑 포트 동적 주입", StepStatus.RESOLVED, "CODE_CHANGE",
                            """
Testcontainers 본래 방식대로 **랜덤 포트**로 띄우고, 그 포트를 Spring 프로퍼티에 동적 주입했다.
```kotlin
@Container
val db = PostgreSQLContainer("postgres:16-alpine")

@DynamicPropertySource
fun props(reg: DynamicPropertyRegistry) {
    reg.add("spring.datasource.url", db::getJdbcUrl)
    reg.add("spring.datasource.username", db::getUsername)
    reg.add("spring.datasource.password", db::getPassword)
}
```
이제 포트가 매번 달라 좀비/병렬과 충돌하지 않는다.
""".trim(),
                            insight = "컨테이너 포트는 절대 고정하지 말 것 — getJdbcUrl/getMappedPort 로 받는다.",
                        ),
                        PersonalStep(
                            "Ryuk 정리 보장으로 재발 봉인", StepStatus.RESOLVED, "CONFIG",
                            """
좀비 재발을 막으려 reuse 를 끄고 Ryuk 가 런 종료 시 확실히 회수하게 두었다.
```properties
testcontainers.reuse.enable=false
testcontainers.ryuk.disabled=false
```
""".trim(),
                        ),
                        PersonalStep(
                            "병렬 10회 반복 통과 검증", StepStatus.RESOLVED, "VERIFY",
                            """
의심을 재현한 그 조건(병렬 + 좀비 잔존)에서 10회 연속 돌려 충돌 0건.
```bash
for i in ${'$'}(seq 1 10); do ./gradlew test --tests '*IntegrationTest' || break; done
# BUILD SUCCESSFUL x10
```
""".trim(),
                        ),
                    ),
                    solutionTitle = "고정 포트를 버리고 매핑 포트를 동적 주입 + Ryuk 정리로 충돌 봉인",
                    attachments = listOf(
                        PersonalAtt(
                            Att.TERMINAL, "port-conflict.svg", "좀비 컨테이너의 포트 점유", "테스트 기동 전 5432를 물고 있던 좀비 컨테이너 — 충돌의 직접 원인",
                            svgTerminal(
                                "docker ps / lsof — before fix",
                                listOf(
                                    "\$ docker ps --filter publish=5432",
                                    "a1b2c3  postgres:16-alpine  Up 2h  0.0.0.0:5432->5432/tcp",
                                    "\$ lsof -i :5432",
                                    "com.docker 931 user TCP *:postgresql (LISTEN)",
                                    "ERROR Bind for 0.0.0.0:5432 failed: port is already allocated",
                                ),
                            ),
                        ),
                    ),
                    snippets = listOf(
                        PersonalSnippet(
                            title = "핵심 수정: 랜덤 포트 + 동적 주입", language = "kotlin",
                            file = "src/test/kotlin/IntegrationTest.kt", lineRange = "18-30",
                            caption = "고정 포트를 제거하고 매핑 포트를 DynamicPropertySource 로 주입",
                            code = """
@Container
val db = PostgreSQLContainer("postgres:16-alpine")

@DynamicPropertySource
fun props(reg: DynamicPropertyRegistry) {
    reg.add("spring.datasource.url", db::getJdbcUrl)
}
""".trim(),
                        ),
                    ),
                    comments = listOf(
                        PersonalComment(
                            "reuse 로 우회하려다 더 꼬였던 게 교훈. 문제를 '기동 비용'으로 잘못 정의했었다.",
                            reply = "랜덤 포트 + Ryuk 조합으로 바꾸니 플래키 완전히 사라짐 👍",
                        ),
                    ),
                ),
            )
            // ── 2) 코루틴 블로킹 I/O (IN_PROGRESS) ──────────────────────────────────
            add(
                PersonalSeed(
                    title = "이벤트 루프를 막던 숨은 블로킹 I/O — 리포트 API p99 2.4s 급등 추적",
                    project = "side-project/api",
                    status = ErrorCaseStatus.IN_PROGRESS,
                    severityCode = 2,
                    daysAgo = 1, hoursAgo = 6,
                    tags = listOf("kotlin", "coroutines", "webflux", "performance", "blocking"),
                    exceptionClass = null,
                    exceptionMessage = null,
                    stackTrace = """
# 예외가 아니라 BlockHound 가 이벤트 루프 스레드에서 블로킹 호출을 감지한 경고
reactor.blockhound.BlockingOperationError: Blocking call! java.io.FileInputStream#readBytes
    at app.report.PdfRenderer.loadFont(PdfRenderer.kt:41)
    at app.report.PdfRenderer.render(PdfRenderer.kt:66)
    at app.report.ReportService.build(ReportService.kt:88)
""",
                    description = """
리포트 생성 API의 **p99가 평소 180ms → 피크 2.4s** 로 치솟았다. CPU/GC는 멀쩡한데 응답만 밀리는 — 전형적 **스레드 기아** 신호. 범인은 suspend 함수 안에서 `Dispatchers.IO` 없이 폰트 파일을 읽던 **숨은 블로킹 I/O**.

- 에러율은 정상, **지연만** 폭증(동시성 ↑ 일수록 비선형)
- 이벤트 루프 스레드 = CPU 코어 수(4) → 4개가 I/O에 묶이면 전체가 밀림
- 아직 스테이징 재검증 전이라 **진행 중(IN_PROGRESS)**

> "suspend 면 non-blocking" 이라는 착각이 만든 사고.
""",
                    steps = listOf(
                        PersonalStep(
                            "BlockHound로 블로킹 지점 특정", StepStatus.RESOLVED, "INVESTIGATION",
                            """
테스트 프로파일에 BlockHound 를 붙이고 부하를 주자 정확히 어디서 루프 스레드가 막히는지 떴다.
```kotlin
// build.gradle.kts (test)
testImplementation("io.projectreactor.tools:blockhound:1.0.8.RELEASE")
// 경고: Blocking call! FileInputStream#readBytes at PdfRenderer.loadFont(PdfRenderer.kt:41)
```
""".trim(),
                            insight = "지연 문제는 추측 말고 BlockHound/프로파일러로 '막힌 스레드'를 눈으로 본다.",
                        ),
                        PersonalStep(
                            "문제 코드 확인", StepStatus.RESOLVED, "INVESTIGATION",
                            """
suspend 함수인데 파일 읽기를 그대로 호출 — 이벤트 루프 스레드에서 블로킹.
```kotlin
suspend fun render(doc: Doc): ByteArray {
    val font = File(doc.fontPath).readBytes() // ← 루프 스레드에서 블로킹 I/O
    return pdf(doc, font)
}
```
""".trim(),
                        ),
                        PersonalStep(
                            "이벤트 루프 스레드만 늘려봄 — 실패", StepStatus.FAILED, "CONFIG",
                            """
급한 마음에 Netty 워커 스레드 수부터 키워봤다.
```properties
reactor.netty.ioWorkerCount=16
```
p99가 2.4s → 1.6s 로 **살짝** 나아졌을 뿐, 동시성을 더 올리니 그대로 폭증. 블로킹을 루프에서 안 치우면 스레드만 늘려도 **근본 해결이 아님**을 확인하고 되돌림.
""".trim(),
                            insight = "블로킹을 루프에 둔 채 스레드만 늘리는 건 둑에 양동이 붓기 — 원인을 옮겨야 한다.",
                        ),
                        PersonalStep(
                            "withContext(Dispatchers.IO)로 오프로드", StepStatus.RESOLVED, "CODE_CHANGE",
                            """
블로킹 구간을 전용 IO 풀로 옮겼다.
```kotlin
suspend fun render(doc: Doc): ByteArray = withContext(Dispatchers.IO) {
    val font = File(doc.fontPath).readBytes()
    pdf(doc, font)
}
```
루프 스레드는 즉시 반환되고 I/O는 IO 디스패처가 감당.
""".trim(),
                            insight = "블로킹 I/O는 반드시 Dispatchers.IO 로 — 루프 스레드는 '절대 막지 않는다'가 원칙.",
                        ),
                        PersonalStep(
                            "부하 테스트로 p99 회복 확인(로컬)", StepStatus.IN_PROGRESS, "VERIFY",
                            """
동시 50에서 재부하 — 로컬은 회복. 스테이징 재검증이 남아 IN_PROGRESS.
```bash
k6 run --vus 50 --duration 2m load/report.js
# http_req_duration p(99)=220ms (before 2.4s)
```
""".trim(),
                        ),
                    ),
                    solutionTitle = null,
                    attachments = listOf(
                        PersonalAtt(
                            Att.CHART, "p99-before.svg", "동시성별 p99 (개선 전)", "동시 요청이 늘수록 p99가 비선형 폭증 — 스레드 기아의 전형",
                            svgChart("리포트 API p99 (개선 전)", "ms", listOf(180, 240, 520, 1100, 1800, 2400), accent = "#ef4444"),
                        ),
                        PersonalAtt(
                            Att.JSON, "blockhound-report.json", "BlockHound 감지 요약", "루프 스레드에서 감지된 블로킹 호출 상위 지점",
                            """
{
  "detections": [
    { "thread": "reactor-http-nio-3", "call": "java.io.FileInputStream#readBytes",
      "at": "app.report.PdfRenderer.loadFont(PdfRenderer.kt:41)", "count": 1284 }
  ],
  "window": "2026-10-17T20:05:00Z/PT5M",
  "p99_ms": 2400
}
""".trim(),
                        ),
                    ),
                    snippets = listOf(
                        PersonalSnippet(
                            title = "핵심 수정: 블로킹 I/O 오프로드", language = "kotlin",
                            file = "app/report/PdfRenderer.kt", lineRange = "60-70",
                            caption = "withContext(Dispatchers.IO) 로 전용 풀로 이동",
                            code = """
suspend fun render(doc: Doc): ByteArray = withContext(Dispatchers.IO) {
    val font = File(doc.fontPath).readBytes()
    pdf(doc, font)
}
""".trim(),
                        ),
                    ),
                    comments = listOf(
                        PersonalComment(
                            "스레드 수부터 늘린 게 부끄럽다. BlockHound를 CI 게이트로 넣어서 블로킹 유입을 원천 차단하는 중.",
                        ),
                    ),
                ),
            )
            // ── 3) JPA Lazy / N+1 (RESOLVED) ───────────────────────────────────────
            add(
                PersonalSeed(
                    title = "닫힌 세션 뒤에서 터진 LazyInitializationException + 숨어있던 N+1",
                    project = "side-project/api",
                    status = ErrorCaseStatus.RESOLVED,
                    severityCode = 2,
                    daysAgo = 5, hoursAgo = 1,
                    tags = listOf("jpa", "hibernate", "lazy", "n+1", "transaction"),
                    exceptionClass = "org.hibernate.LazyInitializationException",
                    exceptionMessage = "could not initialize proxy [Customer#7] - no Session",
                    stackTrace = """
org.hibernate.LazyInitializationException: could not initialize proxy [app.order.Customer#7] - no Session
    at org.hibernate.proxy.AbstractLazyInitializer.initialize(AbstractLazyInitializer.java:170)
    at org.hibernate.proxy.AbstractLazyInitializer.getImplementation(AbstractLazyInitializer.java:310)
    at app.order.OrderResponse.from(OrderResponse.kt:27)
    at app.order.OrderController.get(OrderController.kt:55)
""",
                    description = """
주문 상세 API가 **특정 주문에서만 100% 500** 을 냈다. 스택 최상단은 `LazyInitializationException` — 트랜잭션이 닫힌 뒤 **컨트롤러에서** 지연로딩 연관을 건드린 전형. 파다 보니 목록 API엔 **N+1** 까지 숨어 있었다.

- 상세 API: 특정 주문 **100% 500**(연관 customer 접근 시점)
- 목록 API: 동작하지만 **쿼리 수가 주문 수에 비례**(느림)
- 공통 뿌리: **연관 로딩 전략 미설계**

> `open-in-view=false` 가 문제를 '숨기지 않고 바로 드러내' 준 고마운 기본값이었다.
""",
                    steps = listOf(
                        PersonalStep(
                            "예외 스택에서 매핑 위치 특정", StepStatus.RESOLVED, "INVESTIGATION",
                            """
스택 최상단이 `OrderResponse.from` → **컨트롤러에서** 호출되며 lazy 연관 `order.customer` 를 접근하는 순간 세션이 이미 닫혀 있었다.
```text
LazyInitializationException: could not initialize proxy [Customer#7] - no Session
    at OrderResponse.from(OrderResponse.kt:27)
    at OrderController.get(OrderController.kt:55)
```
""".trim(),
                        ),
                        PersonalStep(
                            "N+1 동반 여부 확인", StepStatus.RESOLVED, "INVESTIGATION",
                            """
p6spy 로 목록 호출의 실제 쿼리를 찍어보니 주문 수만큼 customer select 가 쏟아졌다.
```sql
-- 목록 1회 호출 로그(요약)
select * from orders ...;            -- 1
select * from customer where id=?;   -- N 번 반복
```
""".trim(),
                            insight = "Lazy 예외와 N+1 은 같은 뿌리(연관 로딩 전략 부재)에서 자주 함께 온다.",
                        ),
                        PersonalStep(
                            "open-in-view=true 로 되돌려 임시회피 — 실패(기각)", StepStatus.FAILED, "CONFIG",
                            """
"세션을 뷰까지 열어두면 예외는 사라지잖아" 싶어 되돌려봤다.
```yaml
spring.jpa.open-in-view: true
```
500은 사라졌지만 **커넥션을 요청 끝까지 잡고**(풀 압박) **N+1도 그대로** 남아 근본 문제를 **가리기만** 했다. 팀 규칙상도 금지 — 기각.
""".trim(),
                            insight = "open-in-view 로 Lazy 예외를 '없애는' 건 증상 은폐다 — 경계를 지키는 쪽으로 간다.",
                        ),
                        PersonalStep(
                            "fetch join 으로 연관 즉시 로딩", StepStatus.RESOLVED, "CODE_CHANGE",
                            """
필요한 연관을 한 번에 가져오도록 쿼리를 바꿨다.
```kotlin
@Query("select o from Order o join fetch o.customer where o.id = :id")
fun findWithCustomer(id: Long): Order?
```
목록도 `join fetch` 로 전환해 N+1 제거.
""".trim(),
                            insight = "연관이 항상 필요하면 fetch join — 1+N 을 1 로.",
                        ),
                        PersonalStep(
                            "DTO 매핑을 트랜잭션 내부로 이동", StepStatus.RESOLVED, "CODE_CHANGE",
                            """
매핑을 서비스의 트랜잭션 경계 안으로 옮겨 세션이 살아있을 때 수행.
```kotlin
@Transactional(readOnly = true)
fun get(id: Long): OrderResponse =
    orderRepository.findWithCustomer(id)?.let(OrderResponse::from)
        ?: throw OrderNotFound(id)
```
""".trim(),
                            insight = "DTO 매핑은 트랜잭션 경계 안에서. 컨트롤러는 이미 닫힌 세션이다.",
                        ),
                        PersonalStep(
                            "쿼리 수 회귀 검증", StepStatus.RESOLVED, "VERIFY",
                            """
목록 호출 쿼리 수가 1+N → 1 로 줄고 상세 500 재현 0건.
```text
before: 1 + 40 queries   after: 1 query
```
""".trim(),
                        ),
                    ),
                    solutionTitle = "fetch join + 매핑을 트랜잭션 경계 안으로 이동해 Lazy 예외·N+1 동시 해결",
                    attachments = listOf(
                        PersonalAtt(
                            Att.CHART, "query-count-before.svg", "목록 API 쿼리 수 (fetch join 전)", "주문 수 증가에 따른 쿼리 수 — 1+N 로 선형 증가",
                            svgChart("목록 API 쿼리 수 (fetch join 전)", "queries", listOf(2, 6, 11, 21, 41, 81), accent = "#6366f1"),
                        ),
                    ),
                    snippets = listOf(
                        PersonalSnippet(
                            title = "fetch join 쿼리", language = "kotlin",
                            file = "app/order/OrderRepository.kt", lineRange = "22-28",
                            caption = "연관을 한 번에 로딩해 Lazy 예외/N+1 동시 해결",
                            code = """
@Query("select o from Order o join fetch o.customer where o.id = :id")
fun findWithCustomer(id: Long): Order?
""".trim(),
                        ),
                    ),
                    comments = listOf(
                        PersonalComment("open-in-view=false 는 불편해 보여도 이런 경계 버그를 조기에 드러내 줘서 결국 이득."),
                    ),
                ),
            )
        }
    }
}
