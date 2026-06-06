package org.studieojavry.coreapi.errorcase.step.domain.model.vo

/**
 * Step 의 결과 상태. 타임라인 카드 아이콘/색상 + 케이스 RESOLVED 추천 트리거.
 *  - RESOLVED    : 이 시도로 해결됨. 등록되면 케이스 RESOLVED 추천 (사용자 확인 후 전환).
 *  - IN_PROGRESS : 부분적 진전(일부 환경에서만 동작, 우회 등). 아직 완전한 해결은 아님.
 *  - FAILED      : 시도했으나 안 됐음. insight 에 원인 한두 문장 남기는 게 권장.
 *
 * 의미가 같은 ErrorCaseStatus 의 RESOLVED/IN_PROGRESS 와 *컨텍스트(step vs case)로* 구분된다.
 */
enum class StepStatus { RESOLVED, IN_PROGRESS, FAILED }
