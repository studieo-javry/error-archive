package org.studieojavry.publishapi.publishment.application.port

import java.time.LocalDateTime

/**
 * 발행물의 *원본 case* 라이프사이클을 lazy 판정하기 위한 core-api 조회.
 *
 * core `POST /internal/error-cases/status` 를 호출 — 존재하는 case 만 `{updatedAt}` 반환.
 * 요청한 caseId 가 응답에 **없으면 삭제된 것**으로 간주.
 */
interface OriginalCaseStatusReaderPort {
    /**
     * caseId → 원본 상태. 응답에 없는 id(=삭제됨)는 map 에 안 담긴다.
     *
     * **null 반환 = core 조회 실패** — 이 경우 호출자는 판정을 *건너뛰어야* 한다.
     * (빈 map 은 "요청한 case 가 모두 삭제됨" 을 뜻하므로 실패와 구분 필수.)
     */
    fun fetchStatuses(caseIds: Set<Long>): Map<Long, Status>?

    data class Status(val updatedAt: LocalDateTime)
}
