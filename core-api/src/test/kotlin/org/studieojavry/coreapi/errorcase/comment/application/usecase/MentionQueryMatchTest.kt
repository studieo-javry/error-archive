package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 멘션 후보 검색어 필터 규칙 단위 테스트.
 * 규칙: 빈 q 는 전부 통과, 그 외엔 displayName 또는 handle 이 q 로 시작(대소문자 무시).
 */
class MentionQueryMatchTest {

    @Test
    fun `빈 검색어는 맥락 후보를 모두 통과시킨다`() {
        assertTrue(MentionCandidatesUseCase.matchesQuery("Alice Kim", "alice", ""))
        assertTrue(MentionCandidatesUseCase.matchesQuery("홍길동", null, ""))
    }

    @Test
    fun `displayName 또는 handle 앞부분이 맞으면 통과`() {
        assertTrue(MentionCandidatesUseCase.matchesQuery("Alice Kim", "alice", "al"))
        assertTrue(MentionCandidatesUseCase.matchesQuery("Alice Kim", "alice", "ALI"))
        assertTrue(MentionCandidatesUseCase.matchesQuery("홍길동", "gildong", "홍"))
        // handle 만 일치
        assertTrue(MentionCandidatesUseCase.matchesQuery("홍길동", "gildong", "gil"))
    }

    @Test
    fun `어느 쪽도 앞부분이 안 맞으면 제외`() {
        assertFalse(MentionCandidatesUseCase.matchesQuery("Jin Hong", "jin", "zz"))
        assertFalse(MentionCandidatesUseCase.matchesQuery("Alice Kim", "alice", "bo"))
        // 중간 단어는 prefix 가 아님 — iam 검색과 같은 규칙
        assertFalse(MentionCandidatesUseCase.matchesQuery("Alice Kim", "alice", "kim"))
        // handle 이 없으면 displayName 만 본다
        assertFalse(MentionCandidatesUseCase.matchesQuery("홍길동", null, "gil"))
    }
}
