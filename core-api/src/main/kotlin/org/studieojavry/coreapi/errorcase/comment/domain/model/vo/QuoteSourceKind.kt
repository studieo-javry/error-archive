package org.studieojavry.coreapi.errorcase.comment.domain.model.vo

/**
 * 인용 댓글의 출처 유형 (확정 — 3종).
 *  - NONE       : 인용 없는 일반 댓글 (응답에서 뱃지 비노출)
 *  - CASE_BODY  : 케이스 본문(description) 인용 — 본문 안 임베드된 스니펫/코드도 본문의 일부로 본다.
 *                 "원문 보기" 는 FE 가 snapshot 으로 본문 내 첫 매치 위치를 찾아 slide.
 *  - STEP       : step body 인용
 *
 * CODE_BLOCK 은 의도적으로 두지 않는다 — 본문에 임베드된 `@snippet(id)` 토큰의
 * 위치 점프는 FE 책임으로 충분히 풀린다(snapshot 첫 매치). 모델·검증·필터 단순화.
 */
enum class QuoteSourceKind { NONE, CASE_BODY, STEP }
