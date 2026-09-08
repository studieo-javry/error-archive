package org.studieojavry.coreapi.errorcase.case.domain.model

import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ErrorCase 도메인 — status 전이 상태머신 + update 불변식 + 태그 정규화.
 * 순수 도메인(Spring/DB 無).
 */
class ErrorCaseTest {

    private fun newCase(
        visibility: Visibility = Visibility.PUBLIC,
        meta: Meta = Meta(workspaceId = null, severity = null),
        title: String = "NPE in OrderService",
    ): ErrorCase = ErrorCase.create(
        ownerUserId = 7L, title = title, project = null, snapshot = null,
        description = null, meta = meta, visibility = visibility,
        snippets = emptyList(), attachments = emptyList(), tags = emptyList(), occurredAt = null,
    )

    @Test
    fun `create 는 OPEN 상태로 시작한다`() {
        assertEquals(ErrorCaseStatus.OPEN, newCase().status)
    }

    @Test
    fun `OPEN 에서 IN_PROGRESS 로 전이 가능`() {
        val c = newCase()
        c.transitionTo(ErrorCaseStatus.IN_PROGRESS, actorUserId = 7L)
        assertEquals(ErrorCaseStatus.IN_PROGRESS, c.status)
    }

    @Test
    fun `OPEN 에서 RESOLVED 로 직접 전이는 불가 (IN_PROGRESS 경유)`() {
        val c = newCase()
        assertThrows<IllegalArgumentException> {
            c.transitionTo(ErrorCaseStatus.RESOLVED, actorUserId = 7L)
        }
    }

    @Test
    fun `RESOLVED 전이는 resolvedAt 과 resolvedByUserId 를 기록한다`() {
        val c = newCase()
        c.transitionTo(ErrorCaseStatus.IN_PROGRESS, actorUserId = 7L)
        c.transitionTo(ErrorCaseStatus.RESOLVED, actorUserId = 42L)
        assertEquals(ErrorCaseStatus.RESOLVED, c.status)
        assertNotNull(c.resolvedAt)
        assertEquals(42L, c.resolvedByUserId)
    }

    @Test
    fun `RESOLVED 에서 다시 IN_PROGRESS 로 가도 resolvedAt 은 유지된다`() {
        val c = newCase()
        c.transitionTo(ErrorCaseStatus.IN_PROGRESS, actorUserId = 7L)
        c.transitionTo(ErrorCaseStatus.RESOLVED, actorUserId = 7L)
        val resolvedAt = c.resolvedAt
        c.transitionTo(ErrorCaseStatus.IN_PROGRESS, actorUserId = 7L)
        assertEquals(ErrorCaseStatus.IN_PROGRESS, c.status)
        assertEquals(resolvedAt, c.resolvedAt) // 기록 유지(timeline 용)
    }

    @Test
    fun `같은 상태로 전이는 no-op`() {
        val c = newCase()
        c.transitionTo(ErrorCaseStatus.OPEN, actorUserId = 7L)
        assertEquals(ErrorCaseStatus.OPEN, c.status)
        assertNull(c.resolvedAt)
    }

    @Test
    fun `CLOSED 는 종료 상태 — 어떤 전이도 불가`() {
        val c = newCase()
        c.transitionTo(ErrorCaseStatus.CLOSED, actorUserId = 7L) // OPEN → CLOSED 허용
        assertThrows<IllegalArgumentException> {
            c.transitionTo(ErrorCaseStatus.OPEN, actorUserId = 7L)
        }
    }

    @Test
    fun `update 는 빈 title 을 거부한다`() {
        val c = newCase()
        assertThrows<IllegalArgumentException> {
            c.update("  ", null, null, null, Meta(null, null), Visibility.PUBLIC)
        }
    }

    @Test
    fun `WORKSPACE 가시성은 workspaceId 를 요구한다`() {
        assertThrows<IllegalArgumentException> {
            newCase(visibility = Visibility.WORKSPACE, meta = Meta(workspaceId = null, severity = null))
        }
        // workspaceId 있으면 통과
        val ok = newCase(visibility = Visibility.WORKSPACE, meta = Meta(workspaceId = 1L, severity = null))
        assertEquals(Visibility.WORKSPACE, ok.visibility)
    }

    @Test
    fun `create 는 200자 초과 title 을 거부한다`() {
        assertThrows<IllegalArgumentException> { newCase(title = "a".repeat(201)) }
    }

    @Test
    fun `normalizeTag 는 trim 소문자화하고 빈 문자열은 null`() {
        assertEquals("k8s", ErrorCase.normalizeTag("  K8s "))
        assertNull(ErrorCase.normalizeTag("   "))
    }

    @Test
    fun `normalizeTag 는 32자 초과를 거부한다`() {
        assertThrows<IllegalArgumentException> { ErrorCase.normalizeTag("a".repeat(33)) }
    }
}
