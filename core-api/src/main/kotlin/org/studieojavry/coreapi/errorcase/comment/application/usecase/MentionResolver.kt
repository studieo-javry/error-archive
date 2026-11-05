package org.studieojavry.coreapi.errorcase.comment.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.comment.domain.model.CommentMention
import org.studieojavry.coreapi.errorcase.shared.application.port.IamUserQueryPort

/**
 * 댓글 본문의 @멘션 파싱 + iam-api 로 식별자 → userId 매핑. 작성·수정이 같은 규칙을 쓰도록 한 곳에 둔다.
 */
@Component
class MentionResolver(
    private val iamUserQuery: IamUserQueryPort,
) {
    /**
     * 식별자마다 [candidates] 를 앞에서부터 해석해 처음 매핑되는 것을 채택(식별자도 그 후보로 저장).
     * 하나도 안 되면 원본 식별자를 userId 없이 남긴다.
     */
    fun resolve(commentId: Long, body: String): List<CommentMention> =
        parse(body).map { ident ->
            candidates(ident).firstNotNullOfOrNull { c -> lookup(c)?.let { c to it } }
                ?.let { (c, uid) -> CommentMention.create(commentId, c, uid) }
                ?: CommentMention.create(commentId, ident, null)
        }

    // handle 우선 해석(FE 멘션 자동완성이 @handle 삽입). 못 찾으면 displayName 폴백(기존 호환).
    private fun lookup(ident: String): Long? = runCatching {
        iamUserQuery.resolveByHandle(ident) ?: iamUserQuery.resolveByDisplayName(ident)
    }.getOrNull()

    companion object {
        // @ 앞이 *문자열 시작* 또는 *공백류* 일 때만 멘션으로 인정.
        // 예) "hello@user" / "`@user" / "me@example.com" → 멘션 아님 (이메일·코드 안의 @ 보호)
        //     "@user", " @user", "\n@user" → 멘션
        // `(?:^|\s)` 의 \s 1 글자는 consumed 되지만, findAll 은 그 다음 인덱스부터 재시도하므로
        // 연속 멘션도 정상 인식한다(예: "@a @b").
        private val MENTION_PATTERN = Regex("(?:^|\\s)@([A-Za-z0-9_.가-힣-]+)")
        private const val MENTIONS_MAX = 20

        // 문장 끝 "@alice." / "@alice-" 처럼 식별자 뒤에 붙은 구두점. handle 은 [a-z0-9-] 이고 끝이 '-' 일 수 없다.
        private val TRAILING_PUNCT = charArrayOf('.', '-', '_')

        // "@alice님" → "alice" (영문/숫자 식별자 뒤에 한글이 바로 붙은 경우)
        private val ASCII_THEN_HANGUL = Regex("^([A-Za-z0-9_.-]+?)[._-]*[가-힣]+$")

        // "@홍길동님" → "홍길동". 긴 것부터 비교. 원본이 먼저 해석되므로 이름이 이 글자로 끝나도 안전.
        private val KOREAN_SUFFIXES = listOf(
            "에게서", "한테서", "이랑", "에게", "한테", "께서", "님", "씨", "께",
            "이", "가", "은", "는", "을", "를", "도", "의", "랑", "와", "과", "아", "야", "에",
        )

        // 코드 블록(``` / ~~~, 닫히지 않으면 끝까지)과 인라인 코드 — 로그·예시 속 @ 는 멘션이 아니다.
        private val FENCED_CODE = Regex("(?ms)^[ \\t]*(```|~~~).*?(^[ \\t]*\\1[^\\n]*$|\\z)")
        private val INLINE_CODE = Regex("`[^`\\n]+`")

        /** 본문에서 멘션 식별자 추출 (코드 제외, 끝 구두점 제거, 중복 제거, 최대 [MENTIONS_MAX]). */
        internal fun parse(body: String): List<String> =
            MENTION_PATTERN.findAll(body.replace(FENCED_CODE, "\n").replace(INLINE_CODE, " "))
                .map { it.groupValues[1].trimEnd(*TRAILING_PUNCT) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MENTIONS_MAX)
                .toList()

        /** 해석 시도 순서: 원본 → 한글 앞 영문 부분 → 조사/호칭 뗀 한글. */
        internal fun candidates(ident: String): List<String> {
            val out = mutableListOf(ident)
            ASCII_THEN_HANGUL.matchEntire(ident)?.let { out += it.groupValues[1].trimEnd(*TRAILING_PUNCT) }
            KOREAN_SUFFIXES.firstOrNull { ident.length > it.length && ident.endsWith(it) }
                ?.let { out += ident.dropLast(it.length) }
            return out.filter { it.isNotEmpty() }.distinct()
        }
    }
}
