package org.studieojavry.coreapi.errorcase.comment.domain.model.vo

/**
 * 댓글에 첨부된 Diff 제안의 *코드 출처* 종류.
 *  - SNIPPET       : `CodeSnippet` 엔티티 — case body / step 안에 `@snippet(markerId)` 로 임베드된 독립 스니펫
 *  - MARKDOWN_CODE : step body 안에 작성된 마크다운 ```...``` 코드블럭 — 별도 엔티티 X
 *
 * BE 는 sourceId 를 **opaque String** 으로 받는다(검증 X). suggestion 은 *제안* 이지
 * *반영 보장* 이 아니므로 약한 결합. dangling sourceId 는 FE 가 처리.
 */
enum class SuggestionSourceType { SNIPPET, MARKDOWN_CODE }
