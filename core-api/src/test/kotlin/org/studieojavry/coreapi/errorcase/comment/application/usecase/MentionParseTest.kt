package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 댓글 본문 멘션 파싱 규칙 단위 테스트.
 * 규칙: @ 앞이 문자열 시작/공백류일 때만 멘션, 끝 구두점은 떼고, 조사·호칭이 붙으면 뗀 후보도 시도.
 */
class MentionParseTest {

    @Test
    fun `문장 시작·공백·줄바꿈·탭 뒤의 @ 만 멘션`() {
        assertEquals(listOf("alice"), MentionResolver.parse("@alice 확인"))
        assertEquals(listOf("alice"), MentionResolver.parse("확인 @alice"))
        assertEquals(listOf("alice"), MentionResolver.parse("첫 줄\n@alice"))
        assertEquals(listOf("alice"), MentionResolver.parse("hi\t@alice"))
        assertEquals(listOf("alice", "bob"), MentionResolver.parse("@alice, @bob 봐주세요"))
    }

    @Test
    fun `앞에 글자가 붙은 @ 는 멘션 아님`() {
        assertEquals(emptyList<String>(), MentionResolver.parse("hey@alice"))
        assertEquals(emptyList<String>(), MentionResolver.parse("메일은 alice@test.com 으로"))
        assertEquals(emptyList<String>(), MentionResolver.parse("(@alice) 참고"))
        assertEquals(emptyList<String>(), MentionResolver.parse("@@alice"))
        assertEquals(emptyList<String>(), MentionResolver.parse("@ 혼자"))
    }

    @Test
    fun `끝에 붙은 마침표·하이픈은 뗀다`() {
        assertEquals(listOf("alice"), MentionResolver.parse("이거 봐줘 @alice."))
        assertEquals(listOf("alice"), MentionResolver.parse("@alice... 아니다"))
        assertEquals(listOf("alice"), MentionResolver.parse("@alice- 확인"))
        // 가운데 하이픈은 handle 일부
        assertEquals(listOf("jin-hong"), MentionResolver.parse("@jin-hong 확인"))
        assertEquals(emptyList<String>(), MentionResolver.parse("@. 점만"))
    }

    @Test
    fun `같은 식별자는 한 번만`() {
        assertEquals(listOf("alice"), MentionResolver.parse("@alice @alice @alice."))
    }

    @Test
    fun `조사·호칭이 붙으면 뗀 후보를 뒤에 둔다`() {
        assertEquals(listOf("alice님", "alice"), MentionResolver.candidates("alice님"))
        assertEquals(listOf("alice에게", "alice"), MentionResolver.candidates("alice에게"))
        assertEquals(listOf("홍길동님", "홍길동"), MentionResolver.candidates("홍길동님"))
        assertEquals(listOf("홍길동에게서", "홍길동"), MentionResolver.candidates("홍길동에게서"))
        // 원본이 먼저라 이름이 조사 글자로 끝나도 원본이 우선 해석된다
        assertEquals(listOf("지아", "지"), MentionResolver.candidates("지아"))
    }

    @Test
    fun `붙은 게 없으면 원본만`() {
        assertEquals(listOf("alice"), MentionResolver.candidates("alice"))
        assertEquals(listOf("홍길동"), MentionResolver.candidates("홍길동"))
        // 조사만 있는 경우 빈 후보는 만들지 않는다
        assertEquals(listOf("님"), MentionResolver.candidates("님"))
    }
}
