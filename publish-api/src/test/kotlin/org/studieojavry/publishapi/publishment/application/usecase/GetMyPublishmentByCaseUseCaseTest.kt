package org.studieojavry.publishapi.publishment.application.usecase

import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GetMyPublishmentByCaseUseCase — 케이스당 canonical 발행물 단건 조회는 **요청자 소유일 때만** 반환.
 * 타인 발행물 존재가 새지 않도록 소유 불일치도 null(컨트롤러 404).
 */
class GetMyPublishmentByCaseUseCaseTest {

    private val repo = mock(CasePublishmentRepositoryPort::class.java)
    private val useCase = GetMyPublishmentByCaseUseCase(repo)

    private fun publishment(owner: Long): CasePublishment {
        val now = LocalDateTime.of(2026, 1, 1, 9, 0)
        val snap = ContentSnapshot(
            originalCaseId = 100L, originalCaseTitle = "t", originalCaseCreatedAt = now,
            description = null, tags = emptyList(), steps = emptyList(),
            solutions = emptyList(), snapshot = null,
        )
        return CasePublishment.rehydrate(
            id = 1L, slug = "abc12345", ownerUserId = owner, originalCaseId = 100L,
            title = "t", summary = null, visibility = Visibility.PUBLIC,
            contentSnapshot = snap, options = PublishOptions.defaults(),
            version = 1, viewCount = 0, downloadCount = 0, publishedAt = now, updatedAt = now,
        )
    }

    @Test
    fun `요청자가 소유자면 발행물을 반환`() {
        given(repo.findByOriginalCaseId(100L)).willReturn(publishment(owner = 7L))
        val result = useCase.invoke(caseId = 100L, requesterUserId = 7L)
        assertEquals("abc12345", result?.slug)
    }

    @Test
    fun `요청자가 소유자가 아니면 null`() {
        given(repo.findByOriginalCaseId(100L)).willReturn(publishment(owner = 7L))
        assertNull(useCase.invoke(caseId = 100L, requesterUserId = 999L))
    }

    @Test
    fun `발행물이 없으면 null`() {
        given(repo.findByOriginalCaseId(100L)).willReturn(null)
        assertNull(useCase.invoke(caseId = 100L, requesterUserId = 7L))
    }
}
