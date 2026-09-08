package org.studieojavry.publishapi.publishment.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SlugGenerator — URL-safe base62, 요청 길이 준수, 충돌 극히 드묾(호출자가 UNIQUE 로 방어). */
class SlugGeneratorTest {

    @Test
    fun `요청한 길이의 슬러그를 만든다`() {
        assertEquals(8, SlugGenerator.generate(8).length)
        assertEquals(12, SlugGenerator.generate(12).length)
    }

    @Test
    fun `base62 문자만 사용한다`() {
        val alnum = Regex("^[0-9a-zA-Z]+$")
        repeat(200) {
            assertTrue(alnum.matches(SlugGenerator.generate(8)))
        }
    }

    @Test
    fun `대량 생성 시 사실상 유일하다`() {
        val slugs = (1..5000).map { SlugGenerator.generate(8) }.toSet()
        assertTrue(slugs.size >= 4999) // 62^8 공간 — 5000개 중 충돌은 0에 수렴
    }
}
