package org.studieojavry.coreapi.agentcapture.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FencedCodePromoterTest {

    private val promoter = FencedCodePromoter(minLines = 2)

    private fun promote(body: String): String {
        val scan = promoter.scan(body)
        val markers = scan.blocks.indices.map { "mk$it" }
        return promoter.render(scan, markers)
    }

    @Test
    fun `언어 태그 블록은 항상 승격(1줄이어도)`() {
        val scan = promoter.scan("앞\n```kotlin\nval x = 1\n```\n뒤")
        assertEquals(1, scan.blocks.size)
        assertEquals("kotlin", scan.blocks[0].language)
        assertEquals("val x = 1", scan.blocks[0].code)
        assertEquals("앞\n@snippet(mk0)\n뒤", promoter.render(scan, listOf("mk0")))
    }

    @Test
    fun `info-string 의 파일경로를 filePathOrClass 로`() {
        val scan = promoter.scan("```kotlin src/main/kotlin/Foo.kt\nfun a() {}\n```")
        assertEquals("kotlin", scan.blocks[0].language)
        assertEquals("src/main/kotlin/Foo.kt", scan.blocks[0].filePathOrClass)
    }

    @Test
    fun `무태그 블록 2줄 이상이면 승격(language=text)`() {
        val scan = promoter.scan("```\nline1\nline2\n```")
        assertEquals(1, scan.blocks.size)
        assertEquals("text", scan.blocks[0].language)
        assertNull(scan.blocks[0].filePathOrClass)
        assertEquals("line1\nline2", scan.blocks[0].code)
    }

    @Test
    fun `무태그 1줄 블록은 승격하지 않고 원문 유지`() {
        val body = "```\nonly one\n```"
        val scan = promoter.scan(body)
        assertEquals(0, scan.blocks.size)
        assertEquals(body, promoter.render(scan, emptyList())) // 펜스 그대로
    }

    @Test
    fun `text 태그 1줄도 승격 안 함(무태그와 동일 취급)`() {
        val scan = promoter.scan("```text\nONE LINE\n```")
        assertEquals(0, scan.blocks.size)
    }

    @Test
    fun `text 태그 2줄 이상은 승격`() {
        val scan = promoter.scan("```text\nA\nB\n```")
        assertEquals(1, scan.blocks.size)
        assertEquals("text", scan.blocks[0].language)
    }

    @Test
    fun `여러 블록 각각 다른 marker 로 치환`() {
        val body = "## h\n```kotlin\nk\n```\n\n중간\n\n```yaml\na: 1\nb: 2\n```"
        val scan = promoter.scan(body)
        assertEquals(2, scan.blocks.size)
        assertEquals("kotlin", scan.blocks[0].language)
        assertEquals("yaml", scan.blocks[1].language)
        val out = promoter.render(scan, listOf("m0", "m1"))
        assertEquals("## h\n@snippet(m0)\n\n중간\n\n@snippet(m1)", out)
    }

    @Test
    fun `닫히지 않은 펜스는 승격 안 하고 원문 유지`() {
        val body = "앞\n```kotlin\nval x = 1\n닫는 펜스 없음"
        val scan = promoter.scan(body)
        assertEquals(0, scan.blocks.size)
        assertEquals(body, promoter.render(scan, emptyList()))
    }

    @Test
    fun `펜스 없으면 변화 없음`() {
        val body = "그냥 **본문** 입니다.\n- 목록"
        val scan = promoter.scan(body)
        assertEquals(0, scan.blocks.size)
        assertEquals(body, promoter.render(scan, emptyList()))
    }

    @Test
    fun `null·blank 는 빈 결과`() {
        assertTrue(promoter.scan(null).blocks.isEmpty())
        assertTrue(promoter.scan("   ").blocks.isEmpty())
    }

    @Test
    fun `승격 블록과 비승격 블록 혼재`() {
        // kotlin(승격) + 1줄 무태그(비승격)
        val body = "```kotlin\nval x = 1\n```\n```\nshort\n```"
        val out = promote(body)
        assertEquals("@snippet(mk0)\n```\nshort\n```", out)
    }
}
