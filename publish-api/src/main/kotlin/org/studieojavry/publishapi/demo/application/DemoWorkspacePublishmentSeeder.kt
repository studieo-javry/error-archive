package org.studieojavry.publishapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.demo.config.DemoPublishmentProperties
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.SlugGenerator
import org.studieojavry.publishapi.publishment.domain.Visibility
import org.studieojavry.publishapi.shared.config.SlugProperties
import java.time.LocalDateTime

/**
 * 데모 워크스페이스 발행물 시더 — 손수 작성한 풍성한 스냅샷(WORKSPACE_PUBLISHMENT_SEED, 10건)을
 * publish_db 에 직접 발행한다. core 왕복 없음(자립적·견고).
 *
 *  - owner = 예약 상수 [DEMO_WORKSPACE_OWNER_ID] (실 사용자 없음) → 게스트 `/me` 에는 안 뜨고
 *    공개 페이지 `/p/{slug}` 로만 노출(공유용 발행 아티클 쇼케이스).
 *  - originalCaseId = 예약 범위 [WS_ORIGIN_BASE]+n (core 에 없는 합성 id — 공개 페이지는 원본 조회 안 함).
 *  - `is_demo = true`. 야간 purge 는 게스트분(owner != 예약상수)만 지우므로 이 10건은 보존.
 *  - 멱등: originalCaseId 로 기존 발행물 있으면 skip → 재부팅/재호출 시 slug 안정.
 *  - 첫 건([GUIDE_SEED_INDEX], HikariCP 503)은 FE 사용 가이드가 여는 아티클이라 고정 slug
 *    ([DemoPublishmentProperties.guideArticleSlug]). 기존에 랜덤 slug 로 시드돼 있으면 그 행의 slug 만 교체한다.
 */
@Service
class DemoWorkspacePublishmentSeeder(
    private val repository: CasePublishmentRepositoryPort,
    private val slugProperties: SlugProperties,
    private val props: DemoPublishmentProperties,
) {
    private val log = KotlinLogging.logger {}

    /** 10건을 멱등 발행. 이미 있으면 건너뛴다. 생성 건수 반환. */
    @Transactional
    fun seed(): Int {
        var created = 0
        WORKSPACE_PUBLISHMENT_SEED.forEachIndexed { idx, case ->
            val originalCaseId = WS_ORIGIN_BASE + (idx + 1)
            val existing = repository.findByOriginalCaseId(originalCaseId)
            if (existing != null) {
                // 가이드 아티클이 예전 랜덤 slug 로 남아 있으면 고정 slug 로 교체(그 외엔 그대로 skip).
                if (idx == GUIDE_SEED_INDEX && existing.slug != props.guideArticleSlug && !repository.existsBySlug(props.guideArticleSlug)) {
                    repository.changeSlug(existing.id!!, props.guideArticleSlug)
                    log.info { "demo guide article slug fixed: ${existing.slug} -> ${props.guideArticleSlug}" }
                }
                return@forEachIndexed
            }
            repository.save(buildPublishment(idx, originalCaseId, case))
            created++
        }
        if (created > 0) log.info { "demo workspace publishments seeded: $created new (of ${WORKSPACE_PUBLISHMENT_SEED.size})" }
        return created
    }

    /**
     * 게스트 1회용 데모 발행물 정리 — 예약 owner([DEMO_WORKSPACE_OWNER_ID], 워크스페이스분)는 보존.
     * 부팅/야간 공용. 별도 빈 메서드라 호출부(Bootstrap)에서 트랜잭션이 프록시로 적용된다.
     */
    @Transactional
    fun purgeGuestDemo(): Int = repository.purgeGuestDemo(DEMO_WORKSPACE_OWNER_ID)

    private fun buildPublishment(idx: Int, originalCaseId: Long, case: WsCase): CasePublishment {
        // 케이스 생성시각 = 과거로 back-date(최신이 위로 오도록 idx 역순 간격). 스텝은 그 뒤로 누적 전개.
        val caseCreatedAt = LocalDateTime.now()
            .minusDays((WORKSPACE_PUBLISHMENT_SEED.size - idx).toLong() * 2)
            .withHour(10).withMinute(0).withSecond(0).withNano(0)

        var cursor = caseCreatedAt
        val stepDocs = case.steps.mapIndexed { i, st ->
            val occurredAt = cursor
            cursor = cursor.plusMinutes((st.durationMinutes ?: 30).toLong())
            ContentSnapshot.StepDoc(
                originalStepId = originalCaseId * 100 + i,
                order = i,
                title = st.title,
                body = prose(st.body),
                outcome = st.outcome,
                occurredAt = occurredAt,
                durationMinutes = st.durationMinutes,
                snippets = st.snippet?.let { listOf(it.toDoc("ws-$originalCaseId-s$i")) } ?: emptyList(),
                attachments = emptyList(),
            )
        }

        val solutionDoc = ContentSnapshot.SolutionDoc(
            originalSolutionId = WS_SOLUTION_ID_BASE + (idx + 1),
            title = case.solution.title,
            body = prose(case.solution.body),
            snippets = case.solution.snippet?.let { listOf(it.toDoc("ws-$originalCaseId-sol")) } ?: emptyList(),
        )

        val snapshot = ContentSnapshot(
            originalCaseId = originalCaseId,
            originalCaseTitle = case.title,
            originalCaseCreatedAt = caseCreatedAt,
            description = prose(case.description),
            tags = case.tags,
            steps = stepDocs,
            solutions = listOf(solutionDoc),
            snapshot = if (case.exceptionClass != null || case.exceptionMessage != null || case.stackTrace != null) {
                ContentSnapshot.ErrorSnapshotDoc(
                    exceptionClass = case.exceptionClass,
                    exceptionMessage = case.exceptionMessage,
                    rawStackTrace = case.stackTrace,
                    fingerprint = case.fingerprint,
                )
            } else null,
        )

        return CasePublishment.create(
            slug = if (idx == GUIDE_SEED_INDEX) guideSlug() else uniqueSlug(),
            ownerUserId = DEMO_WORKSPACE_OWNER_ID,
            originalCaseId = originalCaseId,
            title = case.title,
            summary = prose(case.summary),
            visibility = Visibility.PUBLIC,
            contentSnapshot = snapshot,
            options = PublishOptions(
                showStepDuration = true,
                timeStyle = PublishOptions.TimeStyle.EXACT,
                codeLineNumbers = true,
                authorDisplayName = case.author,
            ),
            demo = true,
        )
    }

    private fun WsSnippet.toDoc(marker: String) = ContentSnapshot.SnippetDoc(
        markerId = marker,
        title = title,
        language = language,
        code = code,
        filePathOrClass = filePathOrClass,
        caption = caption,
    )

    /** 고정 가이드 slug — 다른 발행물이 이미 쓰고 있으면(사실상 없음) 랜덤으로 대체하고 경고. */
    private fun guideSlug(): String {
        val s = props.guideArticleSlug
        if (!repository.existsBySlug(s)) return s
        log.warn { "demo guide slug '$s' is taken by another publishment — falling back to a random slug" }
        return uniqueSlug()
    }

    private fun uniqueSlug(): String {
        repeat(3) {
            val s = SlugGenerator.generate(slugProperties.length)
            if (!repository.existsBySlug(s)) return s
        }
        return SlugGenerator.generate(slugProperties.length + 4)
    }

    companion object {
        /** 워크스페이스 데모 발행물 소유자(예약) — 실 사용자 아님. 야간 purge 가 보존하는 기준 owner. */
        const val DEMO_WORKSPACE_OWNER_ID = 9_000_000L

        /** 합성 originalCaseId 예약 베이스 — core 에 없는 id(공개 페이지는 원본 비조회). */
        const val WS_ORIGIN_BASE = 9_000_000L
        private const val WS_SOLUTION_ID_BASE = 9_900_000L

        /** 사용 가이드 아티클 = 시드 첫 건(HikariCP 503). core 데모 시드의 같은 주제 케이스와 짝. */
        const val GUIDE_SEED_INDEX = 0

        /**
         * 렌더러(HtmlRenderer)가 `**굵게**` 를 지원하지 않아 리터럴 별표가 보이는 것을 막기 위해
         * 프로즈 필드에서 강조 마커만 제거(코드 스니펫은 원문 유지). 인라인 `code`·```펜스```·`>`인용은 그대로.
         */
        private fun prose(text: String): String = text.replace("**", "")
    }
}
