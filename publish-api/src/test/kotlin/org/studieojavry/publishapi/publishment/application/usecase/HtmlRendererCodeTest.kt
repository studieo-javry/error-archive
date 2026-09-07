package org.studieojavry.publishapi.publishment.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * B1 — options.codeLineNumbers 를 코드블록 렌더에 반영.
 */
class HtmlRendererCodeTest {

    private val code = "fun a() {\n    val x = 1 < 2\n}"   // 3 줄, `<` 포함(이스케이프 확인)

    private fun pub(codeLineNumbers: Boolean) = CasePublishment.create(
        slug = "abc123", ownerUserId = 1, originalCaseId = 1,
        title = "t", summary = "s", visibility = Visibility.PUBLIC,
        contentSnapshot = ContentSnapshot(
            originalCaseId = 1, originalCaseTitle = "o", originalCaseCreatedAt = LocalDateTime.now(),
            description = null, tags = emptyList(),
            steps = listOf(
                ContentSnapshot.StepDoc(
                    originalStepId = 1, order = 0, title = "s", body = "b",
                    outcome = "RESOLVED", occurredAt = LocalDateTime.now(), durationMinutes = null,
                    snippets = listOf(
                        ContentSnapshot.SnippetDoc("sn1", "code", "kotlin", code, null, null),
                    ),
                    attachments = emptyList(),
                ),
            ),
            solutions = emptyList(), snapshot = null,
        ),
        options = PublishOptions(codeLineNumbers = codeLineNumbers),
    )

    private fun clCount(html: String) = Regex("<span class=\"cl\">").findAll(html).count()

    @Test
    fun `numbered on - pre has numbered class and 3 line spans`() {
        val html = HtmlRenderer.render(pub(codeLineNumbers = true), forPdf = false)
        assertTrue(html.contains("<pre class=\"code numbered\""), "numbered class")
        assertEquals(3, clCount(html), "3 line spans")
    }

    @Test
    fun `numbered off - no numbered class but still line spans`() {
        val html = HtmlRenderer.render(pub(codeLineNumbers = false), forPdf = false)
        // note: "numbered" 문자열은 <style> CSS 에 항상 있음 → <pre> 태그 클래스만 정확히 검사.
        assertFalse(html.contains("<pre class=\"code numbered\""), "no numbered pre")
        assertTrue(html.contains("<pre class=\"code\""), "plain code class")
        assertEquals(3, clCount(html), "still wrapped in line spans")
    }

    @Test
    fun `forPdf - numbered gated off even when option true`() {
        val html = HtmlRenderer.render(pub(codeLineNumbers = true), forPdf = true)
        assertFalse(html.contains("<pre class=\"code numbered\""), "pdf must not get numbered gutter")
        assertEquals(3, clCount(html), "line spans still present")
    }

    @Test
    fun `line spans joined without newline - no phantom blank lines`() {
        val html = HtmlRenderer.render(pub(codeLineNumbers = true), forPdf = false)
        assertFalse(html.contains("</span>\n<span class=\"cl\">"), "no newline between line spans")
    }

    @Test
    fun `code content is escaped`() {
        val html = HtmlRenderer.render(pub(codeLineNumbers = true), forPdf = false)
        assertTrue(html.contains("val x = 1 &lt; 2"), "angle bracket escaped")
        assertFalse(html.contains("1 < 2"), "raw < must not appear")
    }
}
