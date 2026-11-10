package org.studieojavry.coreapi.agentcapture.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** [CaptureContentSanitizer] — 저장 전 민감정보 제거(이슈 #144 §2). */
class CaptureContentSanitizerTest {

    @Test
    fun `URL 자격증명을 제거한다`() {
        assertEquals(
            "clone https://github.com/acme/repo.git",
            CaptureContentSanitizer.sanitize("clone https://user:pass@github.com/acme/repo.git"),
        )
        assertEquals(
            "git://github.com/x",
            CaptureContentSanitizer.sanitize("git://ghp_tokenonly@github.com/x"),
        )
    }

    @Test
    fun `사용자 홈 절대경로를 틸드로 축약한다`() {
        assertEquals("at ~/code/app/main.kt", CaptureContentSanitizer.sanitize("at /Users/hongjiin/code/app/main.kt"))
        assertEquals("~/proj/x", CaptureContentSanitizer.sanitize("/home/jin/proj/x"))
        assertEquals("""~\proj""", CaptureContentSanitizer.sanitize("""C:\Users\jin\proj"""))
    }

    @Test
    fun `이메일 주소를 가린다`() {
        assertEquals("by [email] today", CaptureContentSanitizer.sanitize("by foo.bar+x@example.com today"))
    }

    @Test
    fun `깨끗한 텍스트는 그대로 둔다`() {
        val clean = "NullPointerException at OrderService.place line 42"
        assertEquals(clean, CaptureContentSanitizer.sanitize(clean))
    }

    @Test
    fun `CaptureContent 의 서사 필드만 정제하고 식별자는 보존한다`() {
        val content = CaptureContent(
            symptom = "crash in /Users/jin/app",
            observedFacts = listOf("fetched https://u:pw@host/r", "ok"),
            hypotheses = listOf("maybe /home/jin/.env leaked"),
            reproduction = Reproduction(steps = listOf("run /Users/jin/run.sh"), executed = true),
            environment = CaptureEnvironment(commitSha = "abc123", diffRef = "/Users/jin/x.diff"),
            unknowns = listOf("contact dev@acme.io"),
        )
        val s = CaptureContentSanitizer.sanitize(content)
        assertEquals("crash in ~/app", s.symptom)
        assertEquals(listOf("fetched https://host/r", "ok"), s.observedFacts)
        assertEquals(listOf("maybe ~/.env leaked"), s.hypotheses)
        assertEquals(listOf("run ~/run.sh"), s.reproduction?.steps)
        assertEquals(true, s.reproduction?.executed)
        assertEquals("abc123", s.environment?.commitSha) // 식별자 보존
        assertEquals("~/x.diff", s.environment?.diffRef)
        assertEquals(listOf("contact [email]"), s.unknowns)
    }

    @Test
    fun `AttemptDetail 과 SolutionContent 를 정제한다`() {
        val detail = CaptureContentSanitizer.sanitize(
            AttemptDetail(title = "try /Users/jin/fix", action = "curl https://t@api/x", evidenceRefs = listOf("ev-1")),
        )
        assertEquals("try ~/fix", detail.title)
        assertEquals("curl https://api/x", detail.action)
        assertEquals(listOf("ev-1"), detail.evidenceRefs) // 참조 ID 보존

        val sol = CaptureContentSanitizer.sanitize(
            SolutionContent(
                description = "edit /home/jin/x",
                clientAttemptIds = listOf("a-1"),
                verification = Verification(command = "node /Users/jin/t.js", evidence = "mail root@box.local"),
            ),
        )
        assertEquals("edit ~/x", sol.description)
        assertEquals(listOf("a-1"), sol.clientAttemptIds) // 참조 ID 보존
        assertEquals("node ~/t.js", sol.verification?.command)
        assertEquals("mail [email]", sol.verification?.evidence)
    }
}
