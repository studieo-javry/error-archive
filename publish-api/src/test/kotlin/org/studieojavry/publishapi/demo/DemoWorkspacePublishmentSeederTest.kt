package org.studieojavry.publishapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.demo.application.DemoWorkspacePublishmentSeeder
import org.studieojavry.publishapi.demo.application.DemoWorkspacePublishmentSeeder.Companion.DEMO_WORKSPACE_OWNER_ID
import org.studieojavry.publishapi.demo.application.DemoWorkspacePublishmentSeeder.Companion.WS_ORIGIN_BASE
import org.studieojavry.publishapi.demo.application.WORKSPACE_PUBLISHMENT_SEED
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * 데모 워크스페이스 발행물 시더 검증 — @SpringBootTest + H2(실 JPA, jsonb 왕복 포함).
 *  - 10건 생성, 멱등(재호출 0건)
 *  - 전부 예약 owner · PUBLIC · is_demo=true · 합성 originalCaseId · 풍성(스텝/솔루션/스냅샷)
 *  - 야간 purge 는 게스트분(owner != 예약상수)만 지우고 워크스페이스 10건은 보존
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional   // 각 테스트를 롤백 격리(H2 공유 상태 간섭 방지) + @Modifying purge 에 트랜잭션 제공
class DemoWorkspacePublishmentSeederTest {

    @Autowired private lateinit var seeder: DemoWorkspacePublishmentSeeder
    @Autowired private lateinit var repository: CasePublishmentRepositoryPort

    @Test
    fun `워크스페이스 발행물 10건을 멱등 시드한다`() {
        val firstRun = seeder.seed()
        assertThat(firstRun).isEqualTo(WORKSPACE_PUBLISHMENT_SEED.size)
        assertThat(WORKSPACE_PUBLISHMENT_SEED).hasSize(10)

        // 재호출 — 멱등(0건 추가)
        assertThat(seeder.seed()).isZero()

        // 각 건 속성 + 풍성도
        WORKSPACE_PUBLISHMENT_SEED.forEachIndexed { idx, _ ->
            val p = repository.findByOriginalCaseId(WS_ORIGIN_BASE + (idx + 1))
            assertThat(p).isNotNull
            p!!
            assertThat(p.ownerUserId).isEqualTo(DEMO_WORKSPACE_OWNER_ID)
            assertThat(p.visibility).isEqualTo(Visibility.PUBLIC)
            assertThat(p.demo).isTrue()
            assertThat(p.title).isNotBlank()
            // errorCaseId=4 급 풍성도: 스텝 다수 + 솔루션 + (대부분) 에러 스냅샷
            assertThat(p.contentSnapshot.steps.size).isGreaterThanOrEqualTo(5)
            assertThat(p.contentSnapshot.solutions).isNotEmpty()
            // 최소 1개 FAILED(막다른 길) 스텝 — 탐정 서사
            assertThat(p.contentSnapshot.steps.any { it.outcome == "FAILED" }).isTrue()
            // 강조 마커(**)가 프로즈에 남지 않았는지 (렌더러 미지원)
            assertThat(p.summary).doesNotContain("**")
            assertThat(p.contentSnapshot.description).doesNotContain("**")
        }

        assertThat(repository.existsDemoByOwner(DEMO_WORKSPACE_OWNER_ID)).isTrue()
    }

    @Test
    fun `야간 purge 는 게스트 데모 발행물만 지우고 워크스페이스는 보존한다`() {
        seeder.seed()

        // 게스트 소유 데모 발행물 한 건을 직접 저장(지연 시드 결과 모사)
        val guestId = 770_123L
        repository.save(minimalGuestDemo(guestId, originalCaseId = 55_001L))
        assertThat(repository.existsDemoByOwner(guestId)).isTrue()

        val purged = repository.purgeGuestDemo(DEMO_WORKSPACE_OWNER_ID)
        assertThat(purged).isEqualTo(1)                              // 게스트분만
        assertThat(repository.existsDemoByOwner(guestId)).isFalse()  // 게스트분 삭제
        assertThat(repository.existsDemoByOwner(DEMO_WORKSPACE_OWNER_ID)).isTrue()  // 워크스페이스 보존
        assertThat(repository.findByOriginalCaseId(WS_ORIGIN_BASE + 1)).isNotNull
    }

    private fun minimalGuestDemo(ownerId: Long, originalCaseId: Long): CasePublishment =
        CasePublishment.create(
            slug = "guest" + originalCaseId,
            ownerUserId = ownerId,
            originalCaseId = originalCaseId,
            title = "게스트 개인 발행물",
            summary = null,
            visibility = Visibility.PUBLIC,
            contentSnapshot = ContentSnapshot(
                originalCaseId = originalCaseId,
                originalCaseTitle = "게스트 개인 케이스",
                originalCaseCreatedAt = LocalDateTime.now(),
                description = "x",
                tags = listOf("demo"),
                steps = listOf(
                    ContentSnapshot.StepDoc(
                        originalStepId = 1, order = 0, title = "s", body = "b",
                        outcome = "RESOLVED", occurredAt = LocalDateTime.now(),
                        durationMinutes = null, snippets = emptyList(), attachments = emptyList(),
                    ),
                ),
                solutions = emptyList(),
                snapshot = null,
            ),
            options = PublishOptions.defaults(),
            demo = true,
        )
}
