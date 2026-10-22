package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
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
 * 왜 지연(lazy)인가: 각 게스트가 '개인/Public 홈' 피드를 **처음 조회하는 순간 그 게스트 소유로 생성**한다
 * (로그인 시점 아님 → 홈을 안 보는 방문자에겐 생성 비용 0). 공용 시드와 달리 접속한 게스트별로 다르다.
 *
 * 가시성: **데모 WS 소속(WORKSPACE)** 로 2개 생성 → Library(워크스페이스 목록)에 "내 케이스"로 노출되어
 * persona 들이 올린 팀 케이스 사이에 섞인다(데모 WS id 는 iam identity 로 조회·캐시).
 *
 * 콘텐츠는 워크스페이스 데모([DemoSeeder])와 동급 — 섹션 가득한 인시던트 리포트 본문 + 코드펜스 스텝 +
 * 인라인 코드 스니펫(@snippet) + SVG 이미지/로그 첨부(@attach) + 솔루션 + 댓글.
 *
 * 효과: 생성 직후 recent-active-cases(소유자 기준)에 뜨고, 자동 watch 로 watchlist-feed 에도 즉시 노출.
 * 정리: 게스트는 1회용이라 케이스가 쌓인다 → [DemoResetService] 야간 리셋의 **데모 WS wipe** 가 함께 지운다
 * (데모 WS 전체 wipe 대상). 레거시 ws=null 개인 케이스는 태그 스코프 purge 가 별도로 정리.
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
    private val identityClient: DemoIamIdentityClient,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    /** 데모 WS id 캐시 — 안정적이라 최초 1회만 iam identity 조회. */
    @Volatile
    private var cachedWorkspaceId: Long? = null

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
        val workspaceId = demoWorkspaceId() ?: return   // identity 조회 실패 시 skip(best-effort).
        val already = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = workspaceId, ownerUserId = userId, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = 1,
            ),
        ).isNotEmpty()
        if (already) return

        SEED.take(GUEST_SEED_COUNT).forEach { seedOne(it, userId, workspaceId) }
        // 생성 전에 빈 결과가 캐시됐을 수 있으니 두 홈 위젯 캐시를 무효화해 즉시 반영.
        // (DashboardCacheInvalidator 는 내부에서 예외를 삼키므로 tx 를 오염시키지 않는다.)
        cacheInvalidator.evictBoth(userId)
        log.info { "guest personal cases seeded (userId=$userId, n=$GUEST_SEED_COUNT, ws=$workspaceId)" }
    }

    /** 데모 WS id — 캐시 우선, 없으면 iam identity 1회 조회. 데모 비활성/조회 실패면 null. */
    private fun demoWorkspaceId(): Long? {
        cachedWorkspaceId?.let { return it }
        val id = identityClient.fetchIdentity()?.takeIf { it.enabled }?.workspaceId ?: return null
        cachedWorkspaceId = id
        return id
    }

    private fun seedOne(s: PersonalSeed, ownerUserId: Long, workspaceId: Long) {
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
        val meta = Meta.create(workspaceId = workspaceId, severityCode = s.severityCode)
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
            visibility = Visibility.WORKSPACE, // 데모 WS 소속 — Library(워크스페이스 목록)에 '내 케이스'로 노출.
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
        // 솔루션 = 비-INVESTIGATION 스텝만(조사 스텝은 솔루션 선택 시 dim 되어 "실제 해결책"이 드러남).
        if (s.solutionTitle != null) {
            val solutionStepIds = s.steps.zip(stepIds)
                .filter { (st, _) -> st.attemptType != "INVESTIGATION" }
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
                snapshot = snapshot, description = description, meta = meta, visibility = Visibility.WORKSPACE,
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

        /** 게스트당 시드할 케이스 수(데모 WS 소속). SEED 정의는 더 많아도 앞에서 이만큼만 사용. */
        private const val GUEST_SEED_COUNT = 2

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
            add(
                PersonalSeed(
                    title = "로컬 통합테스트에서만 재현되는 Testcontainers 포트 충돌",
                    project = "side-project/api",
                    status = ErrorCaseStatus.RESOLVED,
                    severityCode = 3,
                    daysAgo = 2, hoursAgo = 3,
                    tags = listOf("testcontainers", "ci", "flaky", "docker"),
                    exceptionClass = "org.testcontainers.containers.ContainerLaunchException",
                    exceptionMessage = "Could not create/start container",
                    stackTrace = """
org.testcontainers.containers.ContainerLaunchException: Could not create/start container
    at org.testcontainers.containers.GenericContainer.tryStart(GenericContainer.java:563)
    at org.testcontainers.containers.GenericContainer.lambda${'$'}doStart${'$'}0(GenericContainer.java:344)
    at org.testcontainers.containers.GenericContainer.doStart(GenericContainer.java:334)
Caused by: com.github.dockerjava.api.exception.InternalServerErrorException: Status 500: Bind for 0.0.0.0:5432 failed: port is already allocated
    at org.testcontainers.shaded...DefaultInvocationBuilder.execute(DefaultInvocationBuilder.java:277)
""",
                    description = """
### 요약
CI(GitHub Actions)에선 100% 통과하는 통합테스트가 **로컬에서만 가끔** `port is already allocated` 로
Testcontainers 기동에 실패한다. 이전 테스트 런이 남긴 Postgres 컨테이너가 **고정 포트 5432** 를 물고 있는 게 원인.

### 환경
- Testcontainers 1.20.1 · Docker Desktop 4.34 (macOS, Apple Silicon)
- JUnit5 · `@Testcontainers` + `@Container` (static) · Postgres 16-alpine
- 병렬 테스트(`junit.jupiter.execution.parallel.enabled=true`) 사용

### 재현 조건
- 직전 런이 **비정상 종료**(디버그 중단/강제 종료)로 Ryuk 이 컨테이너를 못 치웠을 때
- 또는 두 테스트 클래스가 **동시에** 같은 고정 포트를 요청할 때

### 영향
- 로컬에서만 간헐 실패 → "내 머신에선 안 되는데요"로 원인 추적에 시간 낭비
- CI 는 매번 깨끗한 러너라 안 터져서 **문제가 가려짐**

### 증상(포트 점유 확인)
```bash
${'$'} docker ps --filter publish=5432
CONTAINER ID   IMAGE               STATUS         PORTS
a1b2c3d4e5f6   postgres:16-alpine  Up 2 hours     0.0.0.0:5432->5432/tcp   # ← 좀비 컨테이너
${'$'} lsof -i :5432
com.docker  931  user  ...  TCP *:postgresql (LISTEN)
```

### 원인
`withCreateContainerCmdModifier` 로 호스트 포트를 **5432 로 못박아** 둬서, 좀비 컨테이너나 병렬 런과
**포트가 겹치면 바로 실패**. Testcontainers 의 기본 철학(랜덤 매핑 포트)을 거슬러 쓴 게 화근.

> 아래 타임라인 · 재현 스니펫 · 포트 점유 터미널 캡처 참고.
""",
                    steps = listOf(
                        PersonalStep(
                            "CI vs 로컬 차이 좁히기", StepStatus.RESOLVED, "INVESTIGATION",
                            """
CI 는 매 잡마다 새 러너라 좀비 컨테이너가 없음 → 항상 통과. 로컬은 이전 런 잔재가 남음.
`docker ps` 로 테스트 전 이미 5432 를 물고 있는 컨테이너를 확인했다.
```bash
docker ps -a --filter ancestor=postgres:16-alpine
```
""".trim(),
                            insight = "재현이 '환경 상태'에 의존하면 CI/로컬 차이부터 의심한다.",
                        ),
                        PersonalStep(
                            "고정 포트 매핑 코드 확인", StepStatus.RESOLVED, "INVESTIGATION",
                            """
문제의 설정 — 호스트 포트를 5432 로 고정하고 있었다.
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
                            "고정 포트 제거 → 매핑 포트 동적 주입", StepStatus.RESOLVED, "FIX",
                            """
고정 바인딩을 걷어내고 Testcontainers 가 **랜덤 포트**로 띄우게 한 뒤, 그 포트를 Spring 프로퍼티로 주입.
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
""".trim(),
                            insight = "컨테이너 포트는 절대 고정하지 말 것 — getMappedPort/getJdbcUrl 로 받는다.",
                        ),
                        PersonalStep(
                            "Ryuk 정리 보장 + reuse 끔", StepStatus.RESOLVED, "FIX",
                            """
좀비 컨테이너 재발 방지로 reuse 를 끄고 Ryuk 가 런 종료 시 확실히 회수하게.
```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=false
```
""".trim(),
                        ),
                        PersonalStep(
                            "병렬 실행에서 10회 반복 통과 확인", StepStatus.RESOLVED, "VERIFY",
                            "병렬 테스트로 10회 연속 돌려 포트 충돌 재현 0건. 로컬/CI 동일 그린.",
                        ),
                    ),
                    solutionTitle = "고정 포트를 버리고 매핑 포트를 동적 주입 + Ryuk 정리로 충돌 제거",
                    attachments = listOf(
                        PersonalAtt(
                            Att.TERMINAL, "port-conflict.svg", "포트 점유(좀비 컨테이너)", "테스트 기동 전 5432 를 물고 있는 좀비 컨테이너",
                            svgTerminal(
                                "docker ps / lsof",
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
                            title = "수정 후: 랜덤 포트 + 동적 주입", language = "kotlin",
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
                            "고정 포트는 편한데 CI/로컬 병렬 실행이랑 상극. 랜덤 포트로 바꾸니 플래키 사라짐.",
                            reply = "reuse.enable=false 랑 같이 두니 좀비 컨테이너 재발도 없음 👍",
                        ),
                    ),
                ),
            )
            add(
                PersonalSeed(
                    title = "코루틴 withContext(Dispatchers.IO) 누락으로 이벤트 루프 블로킹",
                    project = "side-project/api",
                    status = ErrorCaseStatus.IN_PROGRESS,
                    severityCode = 2,
                    daysAgo = 1, hoursAgo = 6,
                    tags = listOf("kotlin", "coroutines", "performance", "blocking"),
                    exceptionClass = null,
                    exceptionMessage = null,
                    stackTrace = """
# 예외는 아니고 BlockHound 가 이벤트 루프 스레드에서 블로킹 호출을 감지한 경고
reactor.blockhound.BlockingOperationError: Blocking call! java.io.FileInputStream#readBytes
    at app.report.PdfRenderer.loadFont(PdfRenderer.kt:41)
    at app.report.PdfRenderer.render(PdfRenderer.kt:66)
    at app.report.ReportService.build(ReportService.kt:88)
""",
                    description = """
### 요약
리포트 생성 API 의 **p99 레이턴시가 평소 180ms → 2.4s** 로 급등. BlockHound 가 **이벤트 루프 스레드에서
블로킹 I/O** 를 잡아냄 — suspend 함수 안에서 폰트 파일을 `Dispatchers.IO` 로 오프로드하지 않고 직접 읽고 있었다.

### 환경
- Kotlin Coroutines 1.8 · Spring WebFlux (Netty)
- PDF 렌더링: 폰트/이미지 파일 로딩(블로킹) 포함
- 이벤트 루프 스레드 수 = CPU 코어 수(4)

### 가설과 확인
- 블로킹 I/O 가 **소수의 이벤트 루프 스레드**를 점유 → 동시 요청이 몰리면 전체가 밀림
- 부하 테스트(동시 50)에서 p99 급등 재현 + BlockHound 경고 다발

### 지표(개선 전)
아래 첨부 차트 — 동시성이 올라갈수록 p99 가 비선형으로 폭증.

### 진행 상황
IO 디스패처로 오프로드하는 수정 중. 로컬 부하 테스트에선 회복 확인, 스테이징 재검증 대기.
""",
                    steps = listOf(
                        PersonalStep(
                            "BlockHound 로 블로킹 지점 특정", StepStatus.RESOLVED, "INVESTIGATION",
                            """
테스트 프로파일에 BlockHound 설치 후 부하 → `PdfRenderer.loadFont` 의 파일 읽기가 루프 스레드에서 도는 것을 확인.
```kotlin
// build.gradle.kts (test)
testImplementation("io.projectreactor.tools:blockhound:1.0.8.RELEASE")
```
""".trim(),
                            insight = "suspend 라고 다 논블로킹이 아니다 — 블로킹 I/O 는 반드시 IO 디스패처로.",
                        ),
                        PersonalStep(
                            "문제 코드 확인", StepStatus.RESOLVED, "INVESTIGATION",
                            """
```kotlin
suspend fun render(doc: Doc): ByteArray {
    val font = File(doc.fontPath).readBytes() // ← 루프 스레드에서 블로킹 I/O
    return pdf(doc, font)
}
```
""".trim(),
                        ),
                        PersonalStep(
                            "withContext(Dispatchers.IO) 로 오프로드", StepStatus.IN_PROGRESS, "FIX",
                            """
블로킹 구간을 전용 IO 풀로 이동.
```kotlin
suspend fun render(doc: Doc): ByteArray = withContext(Dispatchers.IO) {
    val font = File(doc.fontPath).readBytes()
    pdf(doc, font)
}
```
""".trim(),
                        ),
                        PersonalStep(
                            "부하 테스트로 p99 회복 확인", StepStatus.IN_PROGRESS, "VERIFY",
                            "동시 50 재부하에서 p99 2.4s → 220ms 로 회복(로컬). 스테이징 재검증 예정.",
                        ),
                    ),
                    solutionTitle = null,
                    attachments = listOf(
                        PersonalAtt(
                            Att.CHART, "p99-latency.svg", "동시성별 p99 (개선 전)", "동시 요청이 늘수록 p99 가 비선형 폭증",
                            svgChart("리포트 API p99 레이턴시 (개선 전)", "ms", listOf(180, 240, 520, 1100, 1800, 2400), accent = "#ef4444"),
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
                            "BlockHound 를 테스트에 상시로 붙여두니 이런 거 조기에 잡힘.",
                            reply = "맞음. CI 에도 넣어서 블로킹 유입을 게이트로 막는 중.",
                        ),
                    ),
                ),
            )
            add(
                PersonalSeed(
                    title = "JPA @ManyToOne 지연로딩 직렬화 중 LazyInitializationException",
                    project = "side-project/api",
                    status = ErrorCaseStatus.RESOLVED,
                    severityCode = 2,
                    daysAgo = 5, hoursAgo = 1,
                    tags = listOf("jpa", "hibernate", "lazy", "n+1"),
                    exceptionClass = "org.hibernate.LazyInitializationException",
                    exceptionMessage = "could not initialize proxy - no Session",
                    stackTrace = """
org.hibernate.LazyInitializationException: could not initialize proxy [app.order.Customer#7] - no Session
    at org.hibernate.proxy.AbstractLazyInitializer.initialize(AbstractLazyInitializer.java:170)
    at org.hibernate.proxy.AbstractLazyInitializer.getImplementation(AbstractLazyInitializer.java:310)
    at app.order.OrderResponse.from(OrderResponse.kt:27)
    at app.order.OrderController.get(OrderController.kt:55)
""",
                    description = """
### 요약
주문 상세 응답을 만드는 중 `LazyInitializationException`. 트랜잭션(영속성 컨텍스트)이 닫힌 뒤
**컨트롤러 계층**에서 지연로딩 연관(`order.customer`)을 건드린 전형적 케이스. 겸사겸사 목록 API 엔 N+1 도 있었다.

### 환경
- Spring Boot 4 · Hibernate 6 · `spring.jpa.open-in-view=false`
- `Order 1 : N OrderItem`, `Order N : 1 Customer` (모두 LAZY)

### 원인
- DTO 매핑(`OrderResponse.from`)이 **세션 밖(컨트롤러)** 에서 수행 → 프록시 초기화 실패
- 목록 조회는 주문마다 customer 를 따로 조회 → **N+1**

### 영향
- 상세 API 간헐 500(특정 주문에서 100%)
- 목록 API 는 동작하지만 **쿼리 수가 주문 수에 비례** → 느림

### 교훈
조회는 **fetch join 으로 필요한 연관을 한 번에** 가져오고, DTO 매핑은 **트랜잭션 경계 안**에서 한다.
`open-in-view=false` 는 이런 실수를 **빨리 드러내 주는** 좋은 기본값.
""",
                    steps = listOf(
                        PersonalStep(
                            "예외 스택에서 매핑 위치 특정", StepStatus.RESOLVED, "INVESTIGATION",
                            """
스택 최상단이 `OrderResponse.from` → 컨트롤러에서 호출되며 lazy 연관 `order.customer` 접근 시점에 세션이 이미 닫힘.
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
`spring.jpa.show-sql=true` + p6spy 로 목록 호출 시 쿼리 로그 확인 — 주문 수만큼 customer select 발생.
""".trim(),
                            insight = "Lazy 예외와 N+1 은 같은 뿌리(연관 로딩 전략 미설계)에서 자주 함께 온다.",
                        ),
                        PersonalStep(
                            "fetch join 으로 연관 즉시 로딩", StepStatus.RESOLVED, "FIX",
                            """
```kotlin
@Query(""${'"'}
    select o from Order o
    join fetch o.customer
    where o.id = :id
""${'"'})
fun findWithCustomer(id: Long): Order?
```
""".trim(),
                        ),
                        PersonalStep(
                            "DTO 매핑을 트랜잭션 내부로 이동", StepStatus.RESOLVED, "FIX",
                            """
매핑을 `@Transactional(readOnly = true)` 서비스 메서드 안으로 옮겨 세션 경계 안에서 수행.
```kotlin
@Transactional(readOnly = true)
fun get(id: Long): OrderResponse =
    orderRepository.findWithCustomer(id)
        ?.let(OrderResponse::from)
        ?: throw OrderNotFound(id)
```
""".trim(),
                            insight = "DTO 매핑은 트랜잭션 경계 안에서. 컨트롤러는 이미 닫힌 세션이다.",
                        ),
                        PersonalStep(
                            "목록 쿼리 수 회귀 검증", StepStatus.RESOLVED, "VERIFY",
                            "fetch join 적용 후 목록 호출 쿼리 수 1+N → 1 로 감소. 상세 500 재현 0건.",
                        ),
                    ),
                    solutionTitle = "fetch join + 매핑을 트랜잭션 경계 안으로 이동해 lazy 예외·N+1 동시 해결",
                    attachments = listOf(
                        PersonalAtt(
                            Att.CHART, "query-count.svg", "목록 API 쿼리 수 (수정 전/후는 캡션 참고)", "주문 수 증가에 따른 쿼리 수 — fetch join 전(파랑 선)",
                            svgChart("목록 API 쿼리 수 (fetch join 전)", "queries", listOf(2, 6, 11, 21, 41, 81), accent = "#6366f1"),
                        ),
                    ),
                    snippets = listOf(
                        PersonalSnippet(
                            title = "fetch join 쿼리", language = "kotlin",
                            file = "app/order/OrderRepository.kt", lineRange = "22-28",
                            caption = "연관을 한 번에 로딩해 lazy 예외/N+1 동시 해결",
                            code = """
@Query("select o from Order o join fetch o.customer where o.id = :id")
fun findWithCustomer(id: Long): Order?
""".trim(),
                        ),
                    ),
                    comments = listOf(
                        PersonalComment("open-in-view=false 로 두면 이런 게 바로 드러나서 오히려 좋음. 숨기지 말고 경계를 지키자."),
                    ),
                ),
            )
        }
    }
}
