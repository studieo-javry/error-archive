package org.studieojavry.coreapi.errorcase.case.domain.model.vo

/**
 * 케이스(및 그 하위 기록)의 출처. 사람이 직접 작성했는지, AI 에이전트가 수집한 초안을 검토·반영했는지를 구분한다.
 *
 * 잔디/KPI 집계에서 AGENT_IMPORT 는 "직접 작성"과 동일하게 세지 않는다(발행 이벤트의 origin 게이트로 처리).
 * 기본값은 MANUAL — 기존 모든 작성 경로는 손대지 않아도 MANUAL 로 남는다.
 */
enum class ContentSource {
    MANUAL,
    AGENT_IMPORT,
}
