package org.studieojavry.coreapi.errorcase.step.domain.model.vo

import org.studieojavry.coreapi.errorcase.step.domain.model.Step

/**
 * Step 의 결과 상태. 타임라인 카드 아이콘/색상 + 케이스 RESOLVED 추천 트리거.
 *  - SUCCESS: 이 시도로 해결됨. 첫 SUCCESS 가 등록되면 케이스 RESOLVED 추천.
 *  - PARTIAL: 부분적 진전(예: 일부 환경에서만 동작, 우회).
 *  - FAILURE: 시도했으나 안 됐음. insight 에 원인 한두 문장 남기는 게 권장.
 */
enum class StepStatus { SUCCESS, PARTIAL, FAILURE }
