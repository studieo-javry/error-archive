package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DescriptionPreviewTest {

    @Test
    fun `null 과 빈 문자열은 null`() {
        assertNull(DescriptionPreview.of(null))
        assertNull(DescriptionPreview.of(""))
        assertNull(DescriptionPreview.of("   \n\t  "))
    }

    @Test
    fun `짧은 본문은 그대로`() {
        assertEquals("hello world", DescriptionPreview.of("hello world"))
    }

    @Test
    fun `snippet 마커는 code 로 치환`() {
        assertEquals(
            "에러 재현은 [code] 와 같이.",
            DescriptionPreview.of("에러 재현은 @snippet(7a7d35e9) 와 같이.")
        )
    }

    @Test
    fun `attach 마커는 file 로 치환`() {
        assertEquals(
            "로그 [file] 참고",
            DescriptionPreview.of("로그 @attach(file_001) 참고")
        )
    }

    @Test
    fun `복수 마커 + 연속 공백 정규화`() {
        assertEquals(
            "[code] 와 [file]",
            DescriptionPreview.of("@snippet(a)   와\n\n@attach(b)")
        )
    }

    @Test
    fun `정확히 120자는 그대로`() {
        val s = "가".repeat(120)
        val r = DescriptionPreview.of(s)
        assertEquals(120, r!!.length)
        assertEquals(s, r)
    }

    @Test
    fun `121자는 120자로 잘리고 끝에 ellipsis`() {
        val s = "가".repeat(121)
        val r = DescriptionPreview.of(s)
        assertEquals(121, r!!.length)            // 120 + "…"(1)
        assertEquals("가".repeat(120) + "…", r)
    }

    @Test
    fun `B 옵션 — placeholder 가 경계에 걸치면 그 직전까지 후퇴`() {
        // 117자 일반 + " [code]" → 치환 후 "117자 [code]" (124자, [ 가 118 위치)
        // take(120) = "117자 [co" → 깨진 [co 제거 → "117자" + "…"
        val body = "가".repeat(117) + " @snippet(abc12345)"
        val r = DescriptionPreview.of(body)
        // "…" 는 정확히 117자 + "…" 또는 117자 + " " trimEnd 후 "…"
        // " " 는 trimEnd 로 제거
        assertEquals("가".repeat(117) + "…", r)
    }

    @Test
    fun `placeholder 가 경계 안에 완전히 들어가면 유지`() {
        // 113자 + " [code] 이후" → take(120) = 113자 + " [code] 이" → [ < ] → 유지
        val body = "가".repeat(113) + " @snippet(abc) 이후 본문이 길게 이어집니다"
        val r = DescriptionPreview.of(body)
        // 113자 + " [code] 이" = 121자 → take(120) = 113자 + " [code] " + "…"? 정확히 계산
        // 실제: "가...가 [code] 이후 본문이 길게 이어집니다" 의 처음 120자
        // 113 + " [code] " = 113+1+6+1 = 121 → take(120) = 113 + " [code]" (= 120자)
        // lastOpen=114, lastClose=119 → 유지
        assertEquals("가".repeat(113) + " [code]" + "…", r)
    }

    @Test
    fun `경계가 attach placeholder 한가운데에 걸쳐도 제거`() {
        val body = "가".repeat(116) + " @attach(z)"
        val r = DescriptionPreview.of(body)
        // 치환 후 116자 + " [file]" (123자) → take(120) = 116자 + " [fil" → [ > ] → "116자" + "…"
        assertEquals("가".repeat(116) + "…", r)
    }
}
