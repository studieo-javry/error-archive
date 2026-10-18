package org.studieojavry.coreapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.studieojavry.coreapi.demo.application.DemoGuestPersonalSeeder
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility

/**
 * 게스트 개인 케이스 지연 시더 검증 — @SpringBootTest + H2(실 JPA).
 *  - 게스트 소유 개인(workspaceId=null, PRIVATE) 케이스를 생성
 *  - 멱등(두 번 호출해도 중복 생성 X)
 *  - 자동 watch(watchlist row 존재)
 *  - 정리 쿼리(findPersonalCaseIds)가 생성분을 찾아냄
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["demo.enabled=true", "demo.seed-attachments=false"])
class DemoGuestPersonalSeederTest {

    @Autowired private lateinit var seeder: DemoGuestPersonalSeeder
    @Autowired private lateinit var errorCaseRepository: ErrorCaseRepositoryPort
    @Autowired private lateinit var tagRepository: ErrorCaseTagRepositoryPort
    @Autowired private lateinit var watchlistRepository: CaseWatchlistRepositoryPort

    private fun ownedByGuest(guestId: Long) = errorCaseRepository.search(
        ErrorCaseSearchCriteria(
            workspaceId = null, ownerUserId = guestId, status = null, fingerprint = null,
            visibility = null, cursorCreatedAt = null, cursorId = null, limit = 50,
        ),
    )

    @Test
    fun `게스트가 홈 피드를 열면 개인 케이스가 생성되고 멱등하다`() {
        val guestId = 770001L
        assertThat(ownedByGuest(guestId)).isEmpty()

        seeder.ensureSeededForGuest(guestId)
        val afterFirst = ownedByGuest(guestId)
        assertThat(afterFirst).isNotEmpty()

        // 전부 개인(workspaceId=null) + PRIVATE
        afterFirst.forEach {
            assertThat(it.workspaceId).isNull()
            assertThat(it.visibility).isEqualTo(Visibility.PRIVATE)
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
    fun `정리 쿼리가 게스트 개인 케이스를 찾는다`() {
        val guestId = 770002L
        seeder.ensureSeededForGuest(guestId)
        val personalIds = errorCaseRepository.findPersonalCaseIds(200)
        val mine = ownedByGuest(guestId).map { it.id }
        assertThat(personalIds).containsAll(mine)
    }
}
