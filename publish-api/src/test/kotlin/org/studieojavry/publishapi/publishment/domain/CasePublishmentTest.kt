package org.studieojavry.publishapi.publishment.domain

import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CasePublishment 도메인 상태전이 검증 — 순수 도메인(Spring 無).
 * 핵심: republish 는 스냅샷이 *실제로* 바뀐 경우에만 version++; 메타 편집/재공개는 version 유지.
 */
class CasePublishmentTest {

    private fun snapshot(description: String?): ContentSnapshot = ContentSnapshot(
        originalCaseId = 100L,
        originalCaseTitle = "원본 케이스",
        originalCaseCreatedAt = LocalDateTime.of(2026, 1, 1, 9, 0),
        description = description,
        tags = listOf("kotlin"),
        steps = listOf(
            ContentSnapshot.StepDoc(
                originalStepId = 1L, order = 0, title = "step", body = "body",
                outcome = "SUCCESS", occurredAt = LocalDateTime.of(2026, 1, 1, 9, 0),
                durationMinutes = null, snippets = emptyList(), attachments = emptyList(),
            ),
        ),
        solutions = emptyList(),
        snapshot = null,
    )

    private fun newPublishment(): CasePublishment = CasePublishment.create(
        slug = "abc12345",
        ownerUserId = 7L,
        originalCaseId = 100L,
        title = "제목",
        summary = "요약",
        visibility = Visibility.PUBLIC,
        contentSnapshot = snapshot("v1"),
        options = PublishOptions.defaults(),
    )

    @Test
    fun `create 는 version 1, viewCount 0, status LIVE 로 시작`() {
        val p = newPublishment()
        assertEquals(1, p.version)
        assertEquals(0, p.viewCount)
        assertEquals(PublishmentStatus.LIVE, p.status)
        assertEquals(SourceState.LIVE, p.sourceState)
    }

    @Test
    fun `republish 는 스냅샷 내용이 바뀌면 version 을 올린다`() {
        val p = newPublishment()
        p.republish(
            newTitle = "제목2", newSummary = "요약", newVisibility = Visibility.PUBLIC,
            newSnapshot = snapshot("v2-changed"), newOptions = PublishOptions.defaults(),
        )
        assertEquals(2, p.version)
        assertEquals("제목2", p.title)
    }

    @Test
    fun `republish 는 스냅샷이 동일하면 version 을 유지한다 (메타만 변경)`() {
        val p = newPublishment()
        // 동일 내용의 스냅샷 — data class 구조 비교로 '변화 없음' 판정
        p.republish(
            newTitle = "새 제목", newSummary = "새 요약", newVisibility = Visibility.UNLISTED,
            newSnapshot = snapshot("v1"), newOptions = PublishOptions.defaults(),
        )
        assertEquals(1, p.version)
        assertEquals("새 제목", p.title)
        assertEquals(Visibility.UNLISTED, p.visibility)
    }

    @Test
    fun `editMetadata 는 version 을 건드리지 않고 표현 필드만 교체한다`() {
        val p = newPublishment()
        p.editMetadata(
            newTitle = "편집됨", newSummary = null, newVisibility = Visibility.UNLISTED,
            newOptions = PublishOptions.defaults(),
        )
        assertEquals(1, p.version)
        assertEquals("편집됨", p.title)
        assertEquals(null, p.summary)
        assertEquals(Visibility.UNLISTED, p.visibility)
    }

    @Test
    fun `unpublish 후 restore 는 status 를 왕복시킨다`() {
        val p = newPublishment()
        p.unpublish()
        assertEquals(PublishmentStatus.UNPUBLISHED, p.status)
        p.restore()
        assertEquals(PublishmentStatus.LIVE, p.status)
    }

    @Test
    fun `markSourceDeleted 는 sourceState 만 바꾸고 status 는 유지한다`() {
        val p = newPublishment()
        p.markSourceDeleted()
        assertEquals(SourceState.SOURCE_DELETED, p.sourceState)
        assertEquals(PublishmentStatus.LIVE, p.status)
    }

    @Test
    fun `updatedAt 은 상태전이 시 갱신된다`() {
        val p = newPublishment()
        val before = p.updatedAt
        Thread.sleep(2)
        p.unpublish()
        assertNotEquals(before, p.updatedAt)
    }

    @Test
    fun `create 는 빈 title 을 거부한다`() {
        assertThrows<IllegalArgumentException> {
            CasePublishment.create(
                slug = "s", ownerUserId = 1L, originalCaseId = 1L, title = "   ",
                summary = null, visibility = Visibility.PUBLIC,
                contentSnapshot = snapshot("x"), options = PublishOptions.defaults(),
            )
        }
    }

    @Test
    fun `create 는 200자 초과 title 을 거부한다`() {
        assertThrows<IllegalArgumentException> {
            CasePublishment.create(
                slug = "s", ownerUserId = 1L, originalCaseId = 1L, title = "a".repeat(201),
                summary = null, visibility = Visibility.PUBLIC,
                contentSnapshot = snapshot("x"), options = PublishOptions.defaults(),
            )
        }
    }

    @Test
    fun `create 는 500자 초과 summary 를 거부한다`() {
        assertThrows<IllegalArgumentException> {
            CasePublishment.create(
                slug = "s", ownerUserId = 1L, originalCaseId = 1L, title = "ok",
                summary = "a".repeat(501), visibility = Visibility.PUBLIC,
                contentSnapshot = snapshot("x"), options = PublishOptions.defaults(),
            )
        }
    }
}
