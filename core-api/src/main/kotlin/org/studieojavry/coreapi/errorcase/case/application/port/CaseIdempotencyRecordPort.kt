package org.studieojavry.coreapi.errorcase.case.application.port

/**
 * 케이스 생성(POST /error-cases) 요청 멱등성 기록 저장소.
 *
 * `(idempotencyKey, userId)` 가 네임스페이스 — 같은 사용자가 같은 키로 재요청하면
 * 최초에 생성된 error_case id 를 그대로 돌려주기 위한 매핑. 더블클릭/네트워크 재시도로 인한
 * 중복 케이스 생성을 서버에서 차단한다.
 */
interface CaseIdempotencyRecordPort {

    /** `(key, userId)` 로 기존 멱등 기록 조회. 없으면 null. */
    fun find(idempotencyKey: String, userId: Long): Existing?

    /**
     * 새 멱등 기록 저장. `(key, userId)` unique 위반(동시 중복 요청)이면
     * [org.studieojavry.coreapi.errorcase.case.application.usecase.IdempotencyConflictException] throw.
     */
    fun save(idempotencyKey: String, userId: Long, requestHash: String, errorCaseId: Long)

    data class Existing(
        /** 최초 요청 본문 해시 — 같은 키로 다른 payload 재사용 탐지용. */
        val requestHash: String,
        /** 최초 요청으로 생성된 error_case id. */
        val errorCaseId: Long,
    )
}
