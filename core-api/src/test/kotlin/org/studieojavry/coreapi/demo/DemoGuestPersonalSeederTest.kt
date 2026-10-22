package org.studieojavry.coreapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.studieojavry.coreapi.demo.application.DemoGuestPersonalSeeder
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility

/**
 * 게스트 개인 케이스 지연 시더 검증 — @SpringBootTest + H2(실 JPA).
 *  - 게스트 소유 케이스를 **데모 WS 소속(WORKSPACE)** 로 2개 생성(Library 에 '내 케이스'로 노출)
 *  - 멱등(두 번 호출해도 중복 생성 X) · 자동 watch
 *  - 데모 WS id 는 iam `identity` 로 얻으므로 DemoIamIdentityClient 를 목킹
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true", "demo.seed-attachments=false"])
class DemoGuestPersonalSeederTest {

    @Autowired private lateinit var seeder: DemoGuestPersonalSeeder
    @Autowired private lateinit var errorCaseRepository: ErrorCaseRepositoryPort
    @Autowired private lateinit var tagRepository: ErrorCaseTagRepositoryPort
    @Autowired private lateinit var watchlistRepository: CaseWatchlistRepositoryPort
    @MockitoBean private lateinit var identityClient: DemoIamIdentityClient

    @BeforeEach
    fun stubIdentity() {
        Mockito.`when`(identityClient.fetchIdentity())
            .thenReturn(DemoIamIdentityClient.DemoIdentity(enabled = true, workspaceId = DEMO_WS, botUserId = 1L))
    }

    private fun ownedByGuest(guestId: Long) = errorCaseRepository.search(
        ErrorCaseSearchCriteria(
            workspaceId = DEMO_WS, ownerUserId = guestId, status = null, fingerprint = null,
            visibility = null, cursorCreatedAt = null, cursorId = null, limit = 50,
        ),
    )

    @Test
    fun `게스트가 홈 피드를 열면 데모 WS 개인 케이스 2개가 생성되고 멱등하다`() {
        val guestId = 770001L
        assertThat(ownedByGuest(guestId)).isEmpty()

        seeder.ensureSeededForGuest(guestId)
        val afterFirst = ownedByGuest(guestId)
        assertThat(afterFirst).hasSize(2)

        // 데모 WS 소속 + WORKSPACE 가시성 → Library(워크스페이스 목록)에 '내 케이스'로 노출
        afterFirst.forEach {
            assertThat(it.workspaceId).isEqualTo(DEMO_WS)
            assertThat(it.visibility).isEqualTo(Visibility.WORKSPACE)
        }

        // 자동 watch — 각 케이스가 게스트의 watchlist 에 존재
        afterFirst.forEach { c ->
            assertThat(watchlistRepository.exists(c.id, guestId)).isTrue()
        }

        // 식별 태그 부착
        assertThat(tagRepository.findAllByErrorCaseId(afterFirst.first().id))
            .contains(DemoGuestPersonalSeeder.GUEST_PERSONAL_TAG)

        // 멱등: 재호출해도 개수 그대로
        seeder.ensureSeededForGuest(guestId)
        assertThat(ownedByGuest(guestId)).hasSameSizeAs(afterFirst)
    }

    @Test
    fun `게스트 케이스는 데모 WS 소속이라 ws=null 정리 쿼리에는 잡히지 않는다`() {
        val guestId = 770002L
        seeder.ensureSeededForGuest(guestId)
        val mine = ownedByGuest(guestId).map { it.id }
        assertThat(mine).isNotEmpty()

        // 게스트 케이스는 이제 데모 WS(workspaceId != null) 소속 → workspace wipe 로 정리된다.
        // ws=null + 태그 스코프인 레거시 개인-정리 쿼리(2026-10-19 인시던트 가드)에는 (의도대로) 안 잡힌다.
        val wsNullTagged = errorCaseRepository.findDemoGuestPersonalCaseIds(DemoGuestPersonalSeeder.GUEST_PERSONAL_TAG, 200)
        assertThat(wsNullTagged).doesNotContainAnyElementsOf(mine)
    }

    private companion object {
        const val DEMO_WS = 424242L
    }
}
