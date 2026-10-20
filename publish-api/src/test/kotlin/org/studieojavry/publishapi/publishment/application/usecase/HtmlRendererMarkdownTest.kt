package org.studieojavry.publishapi.publishment.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * HtmlRenderer 가 케이스 본문 마크다운의 `#` 헤딩 / `**볼드**` 를 실제 HTML 로 변환하는지.
 * (과거엔 리터럴 `###`/`**` 가 그대로 노출됐다 — 게스트 발행물이 core 본문을 스냅샷하므로.)
 */
class HtmlRendererMarkdownTest {

    private fun publishmentWith(description: String): CasePublishment =
        CasePublishment.create(
            slug = "mdtest01",
            ownerUserId = 1L,
            originalCaseId = 1L,
            title = "렌더 테스트",
            summary = null,
            visibility = Visibility.PUBLIC,
            contentSnapshot = ContentSnapshot(
                originalCaseId = 1L,
                originalCaseTitle = "렌더 테스트",
                originalCaseCreatedAt = LocalDateTime.now(),
                description = description,
                tags = listOf("demo"),
                steps = listOf(
                    ContentSnapshot.StepDoc(
                        originalStepId = 1, order = 0, title = "조사",
                        body = "## 접근\n우선 **로그**를 봤다.",
                        outcome = "RESOLVED", occurredAt = LocalDateTime.now(),
                        durationMinutes = null, snippets = emptyList(), attachments = emptyList(),
                    ),
                ),
                solutions = emptyList(),
                snapshot = null,
            ),
            options = PublishOptions.defaults(),
        )

    @Test
    fun `헤딩과 볼드가 HTML 로 변환되고 리터럴 마커가 남지 않는다`() {
        val p = publishmentWith("### 요약\n주문 상세에서 **LazyInitializationException** 발생.\n\n## 환경\n`Spring Boot 4`.")
        val html = HtmlRenderer.render(p)
        // 본문(article) 범위만 검사 — <head> 의 meta/og description 은 원문(마크다운)을 싣는다(별개).
        val body = html.substringAfter("<article>").substringBefore("</article>")

        // 헤딩 → <h4 class="ph">
        assertThat(body).contains("<h4 class=\"ph\">요약")
        assertThat(body).contains("<h4 class=\"ph\">환경")
        assertThat(body).contains("<h4 class=\"ph\">접근")   // 스텝 본문의 ## 도
        // 볼드 → <strong>
        assertThat(body).contains("<strong>LazyInitializationException</strong>")
        assertThat(body).contains("<strong>로그</strong>")   // 스텝 본문의 볼드도
        // 본문에 리터럴 마커가 남지 않음
        assertThat(body).doesNotContain("### ")
        assertThat(body).doesNotContain("## ")
        assertThat(body).doesNotContain("**")
    }

    @Test
    fun `마크다운 리스트가 ul ol 로 렌더되고 br 로 뭉치지 않는다`() {
        val p = publishmentWith(
            "원인은 두 가지였다.\n\n" +
                "- 커넥션 누수\n- 풀 사이즈 과소\n\n" +
                "조치 순서:\n\n" +
                "1. 트랜잭션 경계 수정\n2. 풀 사이즈 상향",
        )
        val html = HtmlRenderer.render(p)
        val body = html.substringAfter("<article>").substringBefore("</article>")

        // 순서 없는/있는 리스트가 실제 <ul>/<ol><li> 로.
        assertThat(body).contains("<ul class=\"pl\">")
        assertThat(body).contains("<li>커넥션 누수</li>")
        assertThat(body).contains("<li>풀 사이즈 과소</li>")
        assertThat(body).contains("<ol class=\"pl\">")
        assertThat(body).contains("<li>트랜잭션 경계 수정</li>")
        assertThat(body).contains("<li>풀 사이즈 상향</li>")
        // 리스트 항목이 한 문단에 <br> 로 뭉치거나 마커가 리터럴로 남지 않음.
        assertThat(body).doesNotContain("- 커넥션")
        assertThat(body).doesNotContain("1. 트랜잭션")
        assertThat(body).doesNotContain("커넥션 누수<br")
    }
}
