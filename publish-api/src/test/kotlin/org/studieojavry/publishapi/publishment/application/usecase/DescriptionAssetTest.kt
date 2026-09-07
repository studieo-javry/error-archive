package org.studieojavry.publishapi.publishment.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * C1 — 케이스 description 에 태그된 첨부/스니펫이 발행에서 살아남는지.
 */
class DescriptionAssetTest {

    private val now = LocalDateTime.of(2026, 5, 27, 9, 0)

    private fun fullData(desc: String?) = CoreCaseFullData(
        id = 1, ownerUserId = 1, title = "t", description = desc, tags = emptyList(),
        status = "OPEN", visibility = "PUBLIC", createdAt = now, occurredAt = null, snapshot = null,
        // 스텝 본문은 마커를 참조하지 않음 → description 전용 자산임을 보장.
        steps = listOf(CoreCaseFullData.StepData(10, 0, "step", "본문 마커 없음", null, "RESOLVED", null, now)),
        solutions = emptyList(),
        snippets = listOf(CoreCaseFullData.SnippetData("s1", "설정", "yaml", "a: 1", null, null)),
        attachments = listOf(CoreCaseFullData.AttachmentData("a1", "shot.png", "IMAGE", "image/png", "attachments/a1/a1")),
    )

    private fun build(
        full: CoreCaseFullData,
        excludedAtt: Set<String> = emptySet(),
        excludedSnip: Set<String> = emptySet(),
    ) = ContentSnapshotBuilder.build(full, null, null, excludedSnip, excludedAtt, PublishOptions.defaults())

    private fun pub(snap: ContentSnapshot) =
        CasePublishment.create("abc", 1, 1, "t", null, Visibility.PUBLIC, snap, PublishOptions.defaults())

    @Test
    fun `description markers collected as assets (were silently dropped)`() {
        val snap = build(fullData("원인 @attach(a1) 설정 @snippet(s1)"))
        assertEquals(listOf("a1"), snap.descriptionAttachments.map { it.markerId })
        assertEquals(listOf("s1"), snap.descriptionSnippets.map { it.markerId })
        assertTrue(snap.steps[0].attachments.isEmpty(), "step didn't reference → step assets empty")
    }

    @Test
    fun `excluded description asset is dropped, others kept`() {
        val snap = build(fullData("@attach(a1) @snippet(s1)"), excludedAtt = setOf("a1"))
        assertTrue(snap.descriptionAttachments.isEmpty(), "excluded attachment gone")
        assertEquals(listOf("s1"), snap.descriptionSnippets.map { it.markerId })
    }

    @Test
    fun `no markers - empty description assets`() {
        val snap = build(fullData("마커 없는 평범한 본문"))
        assertTrue(snap.descriptionAttachments.isEmpty() && snap.descriptionSnippets.isEmpty())
    }

    @Test
    fun `html and md actually render description assets`() {
        val snap = build(fullData("재현 화면 @attach(a1), 설정 @snippet(s1)"))
        val html = HtmlRenderer.render(pub(snap), forPdf = false, publicBaseUrl = "https://x.co")
        val md = MarkdownRenderer.render(pub(snap), publicBaseUrl = "https://x.co")

        // HTML Context 에 이미지(안정 라우트) + 코드
        assertTrue(html.contains("/p/abc/files/a1"), "html desc image route")
        assertTrue(html.contains("a: 1"), "html desc snippet code")
        // MD 에 이미지 링크 + fenced code
        assertTrue(md.contains("![shot.png](") && md.contains("/p/abc/files/a1"), "md desc image")
        assertTrue(md.contains("```yaml"), "md desc snippet fence")
        // 마커 토큰은 텍스트로 남지 않음(stripMarkers)
        assertFalse(html.contains("@attach("), "html marker stripped")
        assertFalse(md.contains("@snippet("), "md marker stripped")
    }
}
