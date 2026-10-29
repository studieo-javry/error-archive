package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueCaseDraft
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueCaseDraft.SeveritySuggestion
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue → Case 초안 규칙. 본문 예시는 실제 공개 저장소 Issue 양식(vite bug report, spring 자유 서술)을 줄인 것. */
class GithubIssueCaseDraftTest {

    private val t0 = Instant.parse("2026-10-28T00:00:00Z")
    private fun issue(number: Int = 42, title: String = "Pool exhausted") =
        GithubIssueSnapshot(1, number, title, "u", "OPEN", null, t0, null, t0)

    private fun draft(body: String?, labels: List<String> = emptyList(), title: String = "Pool exhausted") =
        GithubIssueCaseDraft.from(issue(title = title), body, labels, "acme/api")

    @Test
    fun `Error Trace - 에러 로그 블록만, 재현 명령·설정·예시 코드는 본문에 남음`() {
        val body = """
            간헐적으로 타임아웃이 납니다.

            ```bash
            mvn spring-boot:run
            ```

            ```yaml
            spring.datasource.hikari.maximum-pool-size: 5
            ```

            ```text
            java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
            	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
            ```
        """.trimIndent()
        val d = draft(body)

        assertEquals(
            "java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n" +
                "\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
            d.paste,
        )
        assertEquals(listOf(false, false, true), d.codeBlocks.map { it.errorLog })
        assertTrue("mvn spring-boot:run" in d.description, "재현 명령은 본문에 남음")
        assertTrue("maximum-pool-size" in d.description, "설정은 본문에 남음")
        assertFalse("HikariPool.createTimeoutException" in d.description, "Error Trace 로 옮긴 로그는 본문에서 빠짐")
        assertTrue("_(에러 로그 → Error Trace 로 옮김)_" in d.description)
        assertTrue(d.description.endsWith("> GitHub Issue: acme/api#42"))
    }

    @Test
    fun `Error Trace - 에러 블록이 하나도 없으면 null (코드 블록이 있어도)`() {
        val d = draft("재현:\n```kotlin\nval a = 1\n```\n```\nnpm run dev\n```")
        assertNull(d.paste)
        assertEquals(2, d.codeBlocks.size)
        assertTrue(d.codeBlocks.none { it.errorLog })
    }

    @Test
    fun `Issue Forms 양식 - 로그 섹션, 체크리스트·No response 섹션 제거, 우회 방법은 Step 후보`() {
        val body = """
            ### Describe the bug

            HMR stops working after upgrading.

            ### Reproduction

            https://stackblitz.com/edit/vite-repro

            ### Steps to reproduce

            ```sh
            npm install && npm run dev
            ```

            ### System Info

            ```shell
            System: OS: macOS 14.5
            Binaries: Node: 22.3.0
            ```

            ### Used Package Manager

            pnpm

            ### Logs

            ```
            [vite] error while updating dependencies:
            failed to resolve import "./foo"
            ```

            ### Workaround

            Pin vite to 5.2.x until fixed.

            ### Additional context

            _No response_

            ### Validations

            - [X] Follow our Code of Conduct
            - [X] Read the docs.
        """.trimIndent()
        val d = draft(body)

        assertEquals("[vite] error while updating dependencies:\nfailed to resolve import \"./foo\"", d.paste, "로그 섹션 + error 단어")
        assertFalse("npm install" in (d.paste ?: ""))
        assertFalse("macOS" in (d.paste ?: ""), "환경 정보는 Error Trace 아님")
        assertTrue("### System Info" in d.description && "macOS 14.5" in d.description, "환경은 본문에 남음")
        assertFalse("### Validations" in d.description, "체크리스트만 있는 섹션 제거")
        assertFalse("### Additional context" in d.description, "_No response_ 섹션 제거")
        assertEquals("Logs", d.codeBlocks.single { it.errorLog }.section)

        val step = assertNotNull(d.workaroundStep)
        assertEquals("우회 방법 (GitHub Issue #42)", step.title)
        assertEquals("Pin vite to 5.2.x until fixed.", step.body)
    }

    @Test
    fun `로그 섹션에 펜스 없이 붙인 스택도 Error Trace 로`() {
        val body = "### Actual behavior\n\n응답이 500\n\n### Error output\n\nException in thread \"main\" java.lang.IllegalStateException: boom\n    at com.acme.Main.main(Main.java:10)"
        val d = draft(body)
        assertTrue(d.paste!!.startsWith("Exception in thread \"main\" java.lang.IllegalStateException: boom"))
        assertFalse("com.acme.Main.main" in d.description)
        assertTrue("응답이 500" in d.description)
    }

    @Test
    fun `코드 블록 안의 # 줄은 섹션 제목으로 보지 않음`() {
        val body = "### Logs\n\n```\n# not a heading\nTypeError: x is undefined\n```\n\n### Workaround\n\n_No response_"
        val d = draft(body)
        assertEquals("# not a heading\nTypeError: x is undefined", d.paste)
        assertNull(d.workaroundStep, "_No response_ 는 후보 아님")
    }

    @Test
    fun `태그 - 자동으로 넣지 않고 후보만, 상태·종류·우선순위 라벨 제외, 주제 접두사는 값만`() {
        val d = draft(
            "본문",
            listOf(
                "type: bug", "status: waiting-for-triage", "p2-edge-case", "pending triage", "help wanted", "Bug",
                "in: web", "theme: ssl", "feat: hmr", "area/k8s", "Postgres", "x".repeat(40),
            ),
        )
        assertEquals(emptyList(), d.tags)
        assertEquals(listOf("web", "ssl", "hmr", "k8s", "postgres"), d.tagSuggestions)
    }

    @Test
    fun `심각도 - 우선순위·심각도 라벨이 있을 때만 후보, 여러 개면 가장 심한 쪽`() {
        assertEquals(SeveritySuggestion(2, "priority: high"), GithubIssueCaseDraft.suggestSeverity(listOf("bug", "priority: high")))
        assertEquals(SeveritySuggestion(1, "P0"), GithubIssueCaseDraft.suggestSeverity(listOf("p3-minor-bug", "P0")))
        assertEquals(SeveritySuggestion(3, "sev3"), GithubIssueCaseDraft.suggestSeverity(listOf("sev3")))
        assertNull(GithubIssueCaseDraft.suggestSeverity(listOf("bug", "in: web", "high")), "접두사 없는 high 는 근거 부족")
    }

    @Test
    fun `보고 시각은 발생 시각으로 넣지 않고 reportedAt 으로만, 빈 제목·빈 본문 대체`() {
        val d = GithubIssueCaseDraft.from(issue(title = "  "), null, emptyList(), "acme/api")
        assertEquals(t0, d.reportedAt)
        assertEquals("GitHub Issue #42", d.title)
        assertEquals("> GitHub Issue: acme/api#42", d.description)
        assertNull(d.paste)
        assertNull(d.severitySuggestion)
    }
}
