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
    fun resolve(commentId: Long, body: String): List<CommentMention> =
        parse(body).map { ident ->
            // handle 우선 해석(FE 멘션 자동완성이 @handle 삽입). 못 찾으면 displayName 폴백(기존 호환).
            val uid = runCatching {
                iamUserQuery.resolveByHandle(ident) ?: iamUserQuery.resolveByDisplayName(ident)
            }.getOrNull()
            CommentMention.create(commentId, ident, uid)
        }

    companion object {
        // @ 앞이 *문자열 시작* 또는 *공백류* 일 때만 멘션으로 인정.
        // 예) "hello@user" / "`@user" / "me@example.com" → 멘션 아님 (이메일·코드 안의 @ 보호)
        //     "@user", " @user", "\n@user" → 멘션
        // `(?:^|\s)` 의 \s 1 글자는 consumed 되지만, findAll 은 그 다음 인덱스부터 재시도하므로
        // 연속 멘션도 정상 인식한다(예: "@a @b").
        private val MENTION_PATTERN = Regex("(?:^|\\s)@([A-Za-z0-9_.가-힣-]+)")
        private const val MENTIONS_MAX = 20

        /** 본문에서 멘션 식별자 추출 (중복 제거, 최대 [MENTIONS_MAX]). */
        internal fun parse(body: String): List<String> =
            MENTION_PATTERN.findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .take(MENTIONS_MAX)
                .toList()
    }
}
