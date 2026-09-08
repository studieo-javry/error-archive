package org.studieojavry.coreapi.errorcase.comment.domain.model

import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.comment.domain.model.vo.QuoteSourceKind
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comment 도메인 — edit(멱등·editedAt), soft delete(멱등·placeholder),
 * 인용 snapshot 절단, init 불변식. 순수 도메인.
 */
class CommentTest {

    private fun newComment(body: String = "원본 댓글") = Comment.create(
        errorCaseId = 1L, authorUserId = 7L, parentCommentId = null,
        body = body, quoteSourceKind = QuoteSourceKind.NONE,
        quoteSourceId = null, quoteSnapshot = null,
    )

    @Test
    fun `edit 는 body 를 바꾸고 editedAt 을 세팅한다`() {
        val c = newComment()
        assertNull(c.editedAt)
        c.edit("수정됨", at = LocalDateTime.of(2026, 1, 1, 9, 0))
        assertEquals("수정됨", c.body)
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), c.editedAt)
    }

    @Test
    fun `edit 는 동일 body 면 no-op (editedAt 미세팅)`() {
        val c = newComment("같은 값")
        c.edit("같은 값")
        assertNull(c.editedAt)
    }

    @Test
    fun `edit 는 빈 body 를 거부한다`() {
        val c = newComment()
        assertThrows<IllegalArgumentException> { c.edit("   ") }
    }

    @Test
    fun `softDelete 는 placeholder 로 비우고 deletedAt 을 세팅한다`() {
        val c = newComment()
        c.softDelete(at = LocalDateTime.of(2026, 1, 1, 9, 0))
        assertTrue(c.isDeleted)
        assertEquals(Comment.DELETED_BODY_PLACEHOLDER, c.body)
        assertNotNull(c.deletedAt)
    }

    @Test
    fun `softDelete 는 멱등 — 두 번째 호출은 deletedAt 을 덮지 않는다`() {
        val c = newComment()
        c.softDelete(at = LocalDateTime.of(2026, 1, 1, 9, 0))
        val first = c.deletedAt
        c.softDelete(at = LocalDateTime.of(2026, 2, 2, 9, 0))
        assertEquals(first, c.deletedAt)
    }

    @Test
    fun `top-level 여부는 parentCommentId 로 판별`() {
        assertTrue(newComment().isTopLevel)
    }

    @Test
    fun `NONE 인용은 sourceId 와 snapshot 을 가질 수 없다`() {
        assertThrows<IllegalArgumentException> {
            Comment.create(
                errorCaseId = 1L, authorUserId = 7L, parentCommentId = null,
                body = "x", quoteSourceKind = QuoteSourceKind.NONE,
                quoteSourceId = 99L, quoteSnapshot = null,
            )
        }
    }

    @Test
    fun `truncateSnapshot 는 QUOTE_SNAPSHOT_MAX 초과분을 자르고 flag 를 세운다`() {
        val long = "a".repeat(Comment.QUOTE_SNAPSHOT_MAX + 50)
        val (snap, truncated) = Comment.truncateSnapshot(long)
        assertEquals(Comment.QUOTE_SNAPSHOT_MAX, snap!!.length)
        assertTrue(truncated)
    }

    @Test
    fun `truncateSnapshot 는 짧은 값은 그대로 두고 빈 값은 null`() {
        val (snap, truncated) = Comment.truncateSnapshot("short")
        assertEquals("short", snap)
        assertFalse(truncated)
        assertNull(Comment.truncateSnapshot("   ").first)
    }
}
