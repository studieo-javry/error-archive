package org.studieojavry.publishapi.publishment.application.usecase

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * A1 — 공개 HTML 의 OG/Twitter 카드 + meta description 렌더 검증(순수 함수).
 */
class HtmlRendererOgTest {

    private val base = "https://app.example.com"

    private fun snapshot(withImage: Boolean, description: String? = null) = ContentSnapshot(
        originalCaseId = 1,
        originalCaseTitle = "orig",
        originalCaseCreatedAt = LocalDateTime.now(),
        description = description,
        tags = listOf("java"),
        steps = listOf(
            ContentSnapshot.StepDoc(
                originalStepId = 10, order = 0, title = "s", body = "body",
                outcome = "RESOLVED", occurredAt = LocalDateTime.now(), durationMinutes = null,
                snippets = emptyList(),
                attachments = if (withImage) listOf(
                    ContentSnapshot.AttachmentRef("img1", "shot.png", "IMAGE", "image/png", "attachments/im/img1"),
                ) else emptyList(),
            ),
        ),
        solutions = emptyList(),
        snapshot = null,
    )

    private fun pub(
        title: String,
        summary: String?,
        withImage: Boolean,
        description: String? = null,
        vis: Visibility = Visibility.PUBLIC,
    ) = CasePublishment.create(
        slug = "k9m2x8", ownerUserId = 7, originalCaseId = 1,
        title = title, summary = summary, visibility = vis,
        contentSnapshot = snapshot(withImage, description), options = PublishOptions.defaults(),
    )

    @Test
    fun `full - og tags with image`() {
        val html = HtmlRenderer.render(pub("NPE 디버깅", "0.3% 결제가 죽던 이유", withImage = true), forPdf = false, publicBaseUrl = base)
        assertTrue(html.contains("""<meta property="og:title" content="NPE 디버깅"/>"""), "og:title")
        assertTrue(html.contains("""<meta property="og:description" content="0.3% 결제가 죽던 이유"/>"""), "og:description")
        assertTrue(html.contains("""<meta property="og:url" content="$base/p/k9m2x8"/>"""), "og:url")
        assertTrue(html.contains("""<meta property="og:image" content="$base/p/k9m2x8/files/img1"/>"""), "og:image")
        assertTrue(html.contains("""<meta name="twitter:card" content="summary_large_image"/>"""), "twitter card large")
        assertTrue(html.contains("""<meta name="description" content="0.3% 결제가 죽던 이유"/>"""), "meta description")
    }

    @Test
    fun `no image - summary card and no og-image`() {
        val html = HtmlRenderer.render(pub("t", "sum", withImage = false), publicBaseUrl = base)
        assertFalse(html.contains("og:image"), "no og:image")
        assertFalse(html.contains("twitter:image"), "no twitter:image")
        assertTrue(html.contains("""<meta name="twitter:card" content="summary"/>"""), "twitter card summary")
    }

    @Test
    fun `no summary - falls back to description snippet with markers stripped`() {
        val html = HtmlRenderer.render(
            pub("t", summary = null, withImage = false, description = "원인은 @attach(x) 캐시 만료   처리"),
            publicBaseUrl = base,
        )
        assertTrue(html.contains("""<meta property="og:description" content="원인은 캐시 만료 처리"/>"""), "stripped snippet")
    }

    @Test
    fun `forPdf - no og or twitter tags`() {
        val html = HtmlRenderer.render(pub("t", "s", withImage = true), forPdf = true, publicBaseUrl = base)
        assertFalse(html.contains("og:"), "no og in pdf")
        assertFalse(html.contains("twitter:"), "no twitter in pdf")
    }

    @Test
    fun `escaping - title with quotes and angle brackets is escaped`() {
        val html = HtmlRenderer.render(pub("a\"<script>", "s", withImage = false), publicBaseUrl = base)
        // 주입된 사용자 title 원문이 이스케이프 없이 그대로 나오면 안 된다.
        // (읽기 진행 바 등 우리 정적 <script> 태그와 충돌하지 않도록 '주입 시퀀스'를 정밀 검사)
        assertFalse(html.contains("a\"<script>"), "raw injected title must not appear")
        assertTrue(html.contains("&quot;") && html.contains("&lt;script&gt;"), "escaped")
    }
}
