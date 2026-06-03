package org.studieojavry.coreapi.errorcase.step.domain.model.vo

import org.studieojavry.coreapi.errorcase.step.domain.model.Step

/**
 * Step 이 어떤 종류의 시도인지(필터/뱃지용). 선택 입력.
 *  - CODE_CHANGE: 애플리케이션 코드 변경
 *  - CONFIG:      설정 변경 (yml/env/feature flag 등)
 *  - DEPENDENCY:  버전·라이브러리·SDK 변경
 *  - ENV:         런타임/인프라 환경 변경 (배포 환경, OS, network)
 *  - ROLLBACK:    이전 상태로 되돌림
 *  - INVESTIGATION: 원인 조사·로깅·재현 시도(직접 변경 없음)
 *  - OTHER
 */
enum class AttemptType {
    CODE_CHANGE, CONFIG, DEPENDENCY, ENV, ROLLBACK, INVESTIGATION, OTHER
}
