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
 * B2 — 소요시간(duration) 공통 포맷터 + 웹=MD 일관성 + EXACT 연도.
 */
class DurationRenderTest {

    @Test
    fun `DurationFormat - hours and minutes preserved, no trimEnd garble`() {
        assertEquals("45m", DurationFormat.humanize(45))
        assertEquals("59m", DurationFormat.humanize(59))
        assertEquals("1h", DurationFormat.humanize(60))
        assertEquals("1h 30m", DurationFormat.humanize(90))    // 옛 버그: MD "1 hr 3"
        assertEquals("2h", DurationFormat.humanize(120))
        assertEquals("2h 30m", DurationFormat.humanize(150))   // 옛 버그: 웹 "2 hr", MD "2 hr 3"
        assertEquals("3h 20m", DurationFormat.humanize(200))   // 옛 버그: MD "3 hr 2"
        assertEquals("2h 25m", DurationFormat.humanize(145))
        assertEquals("1d", DurationFormat.humanize(1440))
        assertEquals("1d 1h", DurationFormat.humanize(1500))
        assertEquals("2d 6h", DurationFormat.humanize(2 * 1440 + 6 * 60))
    }

    private fun pub() = CasePublishment.create(
        slug = "abc", ownerUserId = 1, originalCaseId = 1, title = "t", summary = null,
        visibility = Visibility.PUBLIC,
        contentSnapshot = ContentSnapshot(
            1, "o", LocalDateTime.of(2026, 5, 27, 9, 0), null, emptyList(),
            steps = listOf(
                ContentSnapshot.StepDoc(
                    2, 0, "원인 발견", "b", "RESOLVED",
                    LocalDateTime.of(2026, 5, 29, 19, 42), 150, emptyList(), emptyList(),
                ),
            ),
            solutions = emptyList(), snapshot = null,
        ),
        options = PublishOptions(showStepDuration = true, timeStyle = PublishOptions.TimeStyle.EXACT),
    )

    @Test
    fun `html and md agree on duration and both show year`() {
        val html = HtmlRenderer.render(pub(), forPdf = false)
        val md = MarkdownRenderer.render(pub(), publicBaseUrl = "")

        // 소요시간 웹=MD, 손실/깨짐 없음
        assertTrue(html.contains("· 2h 30m"), "html duration")
        assertTrue(md.contains("*(2h 30m)*"), "md duration")

        // EXACT 시각에 연도
        assertTrue(html.contains("2026-05-29 19:42"), "html exact year")
        assertTrue(md.contains("2026-05-29 19:42"), "md exact year")

        // 옛 버그 문자열이 더 이상 없어야 함
        assertFalse(html.contains("· 2 hr"), "old html '2 hr' gone")
        assertFalse(md.contains("2 hr 3"), "old md '2 hr 3' gone")
        assertFalse(md.contains("5/29 19:42"), "old no-year time gone")
    }
}
