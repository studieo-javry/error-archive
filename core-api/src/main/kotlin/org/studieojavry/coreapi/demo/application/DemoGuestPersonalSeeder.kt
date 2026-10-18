package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.demo.config.DemoProperties
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
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.domain.model.Solution
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator
import org.studieojavry.coreapi.shared.util.FingerprintGenerator
import java.time.LocalDateTime

/**
 * 게스트("로그인 없이 둘러보기") 전용 **개인(personal) 케이스** 지연 시더.
 *
 * 왜 지연(lazy)인가: 개인 케이스는 `workspaceId=null` + `PRIVATE` 라 **소유자 본인에게만** 보인다.
 * 데모봇이 미리 깔아둬도 다른 유저인 게스트에겐 안 보이므로, **각 게스트가 '개인/Public 홈' 피드를
 * 처음 조회하는 순간 그 게스트 소유로 생성**한다(로그인 시점 아님 → 홈을 안 보는 방문자에겐 생성 비용 0).
 *
 * 효과: 생성 직후 recent-active-cases(소유자 기준)에 뜨고, 자동 watch 로 watchlist-feed 에도 즉시 노출.
 * 정리: 게스트는 1회용이라 개인 케이스가 쌓인다 → [DemoResetService] 야간 리셋이 `workspaceId IS NULL`
 * 케이스를 함께 purge(데모 환경에선 개인 케이스=게스트 산물).
 *
 * 멱등: 이미 개인 케이스를 가진 게스트는 skip. `demo.enabled=false` 면 no-op.
 */
@Service
class DemoGuestPersonalSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val addCaseToWatchlistUseCase: AddCaseToWatchlistUseCase,
    private val cacheInvalidator: DashboardCacheInvalidator,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 게스트가 홈 피드를 열 때 호출(컨트롤러에서 guest 일 때만 + `runCatching` 으로 감싸 실패해도 피드 조회를
     * 막지 않음). 단일 `@Transactional` 진입점 — 외부(컨트롤러)에서 호출돼야 프록시 트랜잭션이 적용된다
     * (self-invocation 금지). 예외는 **삼키지 않고** 전파해 부분 생성 시 전체 롤백시킨다(멱등하므로 다음 호출에 재시도).
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

        val case = ErrorCase.create(
            ownerUserId = ownerUserId,
            title = s.title,
            project = s.project,
            snapshot = snapshot,
            description = s.description,
            meta = meta,
            visibility = Visibility.PRIVATE, // 개인 케이스 — 소유자 본인만 read.
            snippets = emptyList(),
            attachments = emptyList(),
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
                snapshot = snapshot, description = s.description, meta = meta, visibility = Visibility.PRIVATE,
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

    private class PersonalStep(val title: String, val status: StepStatus, val attemptType: String, val body: String, val insight: String? = null)
    private class PersonalComment(val body: String, val reply: String? = null)
    private class PersonalSeed(
        val title: String, val project: String, val status: ErrorCaseStatus, val severityCode: Int?,
        val daysAgo: Long, val hoursAgo: Long, val tags: List<String>,
        val exceptionClass: String?, val exceptionMessage: String?, val stackTrace: String,
        val description: String, val steps: List<PersonalStep>, val solutionTitle: String?,
        val comments: List<PersonalComment> = emptyList(),
    )

    companion object {
        /** 게스트 개인 케이스 식별 태그 — 야간 리셋/정리에서 이 산물을 구분하는 용도(+ 버전). */
        const val GUEST_PERSONAL_TAG = "demo-guest-personal"

        private val SEED: List<PersonalSeed> = listOf(
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
                    Caused by: java.net.BindException: Address already in use
                        at java.base/sun.nio.ch.Net.bind0(Native Method)
                """.trimIndent(),
                description = """
                    ## 요약
                    CI 에선 통과하는데 **로컬 통합테스트**에서 가끔 `BindException: Address already in use` 로
                    Testcontainers 가 기동 실패. 이전 테스트 런이 남긴 컨테이너가 포트를 점유한 게 원인.

                    ## 환경
                    - Testcontainers 1.20, Docker Desktop (macOS)
                    - 고정 포트 매핑(`withFixedExposedPort`) 사용 중

                    ## 영향
                    - 로컬에서만 간헐 실패 → "내 머신에선 안 되는데" 디버깅에 시간 낭비
                """.trimIndent(),
                steps = listOf(
                    PersonalStep("고정 포트 매핑 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "`withFixedExposedPort(5432, 5432)` 로 호스트 포트를 못박아 둬서, 이전 런의 컨테이너가 안 내려가면 그대로 충돌."),
                    PersonalStep("랜덤 포트 + 동적 주입으로 변경", StepStatus.RESOLVED, "FIX",
                        "고정 포트를 제거하고 `getMappedPort()` 로 받은 포트를 Spring property 에 동적 주입하도록 변경."),
                    PersonalStep("Ryuk 리소스 정리 확인", StepStatus.RESOLVED, "FIX",
                        "`testcontainers.reuse.enable=false` 로 두고 Ryuk 이 런 종료 시 컨테이너를 확실히 회수하도록."),
                ),
                solutionTitle = "고정 포트를 버리고 매핑 포트를 동적 주입 + Ryuk 정리로 충돌 제거",
                comments = listOf(
                    PersonalComment("고정 포트는 편한데 CI/로컬 병렬 실행이랑 상극이네. 랜덤 포트로 바꾸니 플래키 사라짐."),
                ),
            ),
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
                    # 스택트레이스는 아니고, BlockHound 경고 로그
                    reactor.blockhound.BlockingOperationError: Blocking call! java.io.FileInputStream#readBytes
                        at app.report.PdfRenderer.render(PdfRenderer.kt:41)
                        at app.report.ReportService.build(ReportService.kt:88)
                """.trimIndent(),
                description = """
                    ## 요약
                    리포트 생성 API 의 p99 레이턴시가 갑자기 튐. BlockHound 가 **이벤트 루프 스레드에서
                    블로킹 I/O** 를 잡아냄 — suspend 함수 안에서 `Dispatchers.IO` 로 안 감싸고 파일을 읽고 있었음.

                    ## 가설
                    - PDF 렌더링(파일 읽기/폰트 로딩)이 블로킹인데 IO 디스패처로 오프로드가 안 됨
                    - 동시 요청이 몰리면 적은 수의 이벤트 루프 스레드가 묶여 전체 지연
                """.trimIndent(),
                steps = listOf(
                    PersonalStep("BlockHound 로 블로킹 지점 특정", StepStatus.RESOLVED, "INVESTIGATION",
                        "테스트 프로파일에 BlockHound 를 붙여 `PdfRenderer.render` 의 파일 읽기가 루프 스레드에서 도는 것을 확인."),
                    PersonalStep("렌더링을 withContext(Dispatchers.IO) 로 오프로드", StepStatus.IN_PROGRESS, "FIX",
                        "블로킹 구간을 `withContext(Dispatchers.IO) { ... }` 로 감싸 전용 IO 풀로 이동. 부하 테스트로 p99 회복 확인 중."),
                ),
                solutionTitle = null,
                comments = listOf(
                    PersonalComment(
                        "suspend 함수라고 다 non-blocking 아닌 거 자꾸 까먹음. 블로킹 I/O 는 반드시 IO 디스패처로.",
                        reply = "BlockHound 를 테스트에 상시로 붙여두니 이런 거 조기에 잡힘 👍",
                    ),
                ),
            ),
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
                    org.hibernate.LazyInitializationException: could not initialize proxy [app.order.Order#42] - no Session
                        at org.hibernate.proxy.AbstractLazyInitializer.initialize(AbstractLazyInitializer.java:170)
                        at app.order.OrderResponse.from(OrderResponse.kt:27)
                        at app.order.OrderController.get(OrderController.kt:55)
                """.trimIndent(),
                description = """
                    ## 요약
                    주문 상세 응답 직렬화 중 `LazyInitializationException`. 트랜잭션(세션)이 닫힌 뒤
                    컨트롤러에서 지연로딩 연관을 건드린 전형적 케이스.

                    ## 원인
                    - `open-in-view=false` 인데 DTO 매핑을 **컨트롤러 계층**에서 수행 → 세션 밖
                    - N+1 도 함께 있어 성능까지 저하

                    ## 교훈
                    조회는 **fetch join 으로 필요한 연관을 한 번에** 가져오고, DTO 매핑은 트랜잭션 경계 안에서.
                """.trimIndent(),
                steps = listOf(
                    PersonalStep("예외 스택에서 매핑 위치 확인", StepStatus.RESOLVED, "INVESTIGATION",
                        "`OrderResponse.from` 이 컨트롤러에서 호출되며 lazy 연관 `order.customer` 를 접근 → 세션 종료 후라 예외."),
                    PersonalStep("fetch join 쿼리로 연관 즉시 로딩", StepStatus.RESOLVED, "FIX",
                        "`@Query(\"select o from Order o join fetch o.customer where o.id = :id\")` 로 한 번에 로딩, N+1 도 제거."),
                    PersonalStep("DTO 매핑을 서비스(트랜잭션 내부)로 이동", StepStatus.RESOLVED, "FIX",
                        "매핑을 `@Transactional(readOnly = true)` 서비스 메서드 안으로 옮겨 세션 경계 안에서 수행."),
                ),
                solutionTitle = "fetch join + 매핑을 트랜잭션 경계 안으로 이동해 lazy 예외·N+1 동시 해결",
                comments = listOf(
                    PersonalComment("open-in-view=false 로 두면 이런 게 바로 드러나서 오히려 좋음. 숨기지 말고 경계를 지키자."),
                ),
            ),
        )
    }
}
