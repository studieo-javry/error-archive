package org.studieojavry.publishapi.publishment.application.port

/**
 * 발행(POST /publishments) 요청 멱등성 기록 저장소.
 *
 * `(idempotencyKey, userId)` 가 네임스페이스 — 같은 사용자가 같은 키로 재요청하면
 * 이전에 생성된 발행물 slug 를 그대로 돌려주기 위한 매핑.
 */
interface IdempotencyRecordPort {

    /** `(key, userId)` 로 기존 멱등 기록 조회. 없으면 null. */
    fun find(idempotencyKey: String, userId: Long): Existing?

    /**
     * 새 멱등 기록 저장. `(key, userId)` unique 위반(동시 중복 요청)이면
     * [org.studieojavry.publishapi.publishment.application.usecase.IdempotencyConflictException] throw.
     */
    fun save(idempotencyKey: String, userId: Long, requestHash: String, slug: String)

    /**
     * 발행물 hard delete 시 연결된 멱등 기록 즉시 정리 — slug 로 삭제.
     * (case_publishment 로의 FK 가 없어 남으면 고아. 정리 잡(48h) 전이라도 즉시 회수.)
     * 반환 = 삭제된 행 수(정상 1, 없으면 0).
     */
    fun deleteBySlug(slug: String): Int

    data class Existing(
        /** 최초 요청 본문 해시 — 같은 키로 다른 payload 재사용 탐지용. */
        val requestHash: String,
        /** 최초 요청으로 생성된 발행물 slug. */
        val slug: String,
    )
}
