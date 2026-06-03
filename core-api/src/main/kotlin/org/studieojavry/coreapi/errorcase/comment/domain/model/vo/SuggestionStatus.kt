package org.studieojavry.coreapi.errorcase.comment.domain.model.vo

/**
 * Diff 제안의 처리 상태. 변경 권한은 **케이스 owner 만**(GitHub PR author 와 동치).
 *  - PENDING  : 작성 시 기본값. 작성자가 제안만 한 상태
 *  - APPLIED  : 케이스 owner 가 *수용 의사 표시*. 실제 코드 반영은 작성자가 수동(Phase 1)
 *  - REJECTED : 케이스 owner 가 거부
 */
enum class SuggestionStatus { PENDING, APPLIED, REJECTED }
