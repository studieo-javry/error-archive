package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary.CiConclusion
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubUrl
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubEvidenceDomainTest {

    @Test
    fun `GithubUrl - issues·pull 주소와 꼬리(files, anchor, query) 허용`() {
        assertEquals(GithubUrl("acme", "payment-api", GithubRefKind.ISSUE, 142), GithubUrl.parse("https://github.com/acme/payment-api/issues/142"))
        assertEquals(GithubRefKind.PULL_REQUEST, GithubUrl.parse("https://github.com/acme/payment-api/pull/153/files")?.kind)
        assertEquals(153, GithubUrl.parse("  https://GitHub.com/acme/payment-api/pull/153#discussion_r1 ")?.number)
        assertEquals("acme/my.repo", GithubUrl.parse("http://www.github.com/acme/my.repo/issues/1?x=1")?.fullName)
    }

    @Test
    fun `GithubUrl - github_com 외 호스트, 번호 없음, 다른 경로는 거부`() {
        listOf(
            "https://gitlab.com/acme/api/issues/1",
            "https://github.com.evil.io/acme/api/issues/1",
            "https://github.com/acme/api/issues",
            "https://github.com/acme/api/discussions/3",
            "https://github.com/acme/api/issues/0",
            "github.com/acme/api/issues/1",
            "",
            null,
        ).forEach { assertNull(GithubUrl.parse(it), "should reject: $it") }
    }

    private fun run(name: String, conclusion: String?, status: String = "completed") =
        CiSummary.Run(name, status, conclusion, Instant.parse("2026-10-28T00:00:00Z"))

    @Test
    fun `CiSummary - 실패 하나라도 있으면 FAILURE, 진행 중이 남으면 PENDING, 전부 통과면 SUCCESS`() {
        val failed = CiSummary.of("sha", listOf(run("build", "success"), run("it", "failure"), run("lint", null, "in_progress")))!!
        assertEquals(CiConclusion.FAILURE, failed.conclusion)
        assertEquals(listOf("it"), failed.failedNames)
        assertEquals(3, failed.total)
        assertEquals(1, failed.passed)

        assertEquals(CiConclusion.PENDING, CiSummary.of("sha", listOf(run("build", "success"), run("it", null, "queued")))!!.conclusion)

        val ok = CiSummary.of("sha", listOf(run("build", "success"), run("docs", "skipped"), run("x", "neutral")))!!
        assertEquals(CiConclusion.SUCCESS, ok.conclusion)
        assertEquals(3, ok.passed)

        assertNull(CiSummary.of("sha", emptyList()), "CI 를 안 쓰는 레포")
    }

    @Test
    fun `PR 스냅샷 - 오래된 정보는 무시하고, 이전 커밋 CI 는 최신 요약에 반영하지 않음`() {
        val t1 = Instant.parse("2026-10-28T01:00:00Z")
        val t2 = Instant.parse("2026-10-28T02:00:00Z")
        fun pr(title: String, head: String, updated: Instant) = GithubPullRequestSnapshot(
            1, 7, title, "u", "OPEN", false, "jin", head, "main", 1, 1, 1, t1, null, null, updated,
        )
        val current = pr("new", "sha-2", t2)
        assertFalse(current.applyIfNewer(pr("old", "sha-1", t1)))
        assertEquals("new", current.title)

        assertFalse(current.recordCi(CiSummary("sha-1", CiConclusion.FAILURE, 1, 0, listOf("it"), t1)))
        assertNull(current.ci)
        assertTrue(current.recordCi(CiSummary("sha-2", CiConclusion.SUCCESS, 1, 1, emptyList(), t2)))
        assertFalse(current.isCiStale)

        assertTrue(current.applyIfNewer(pr("newer", "sha-3", t2.plusSeconds(60))))
        assertTrue(current.isCiStale, "새 커밋이 올라왔으니 CI 대기 중")
    }
}
