package org.studieojavry.coreapi.errorcase.case.application.usecase

/**
 * 케이스 생성 멱등성 충돌.
 *  - 같은 `Idempotency-Key` 를 **다른 요청 본문**으로 재사용
 *  - 또는 동시 중복 요청이 unique(key,user) 를 경합 → 이 tx 롤백(클라이언트 재시도 시 replay)
 * → HTTP 409.
 */
class IdempotencyConflictException(message: String) : RuntimeException(message)
