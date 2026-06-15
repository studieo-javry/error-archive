package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa

/**
 * JPA interface projection — `(userId, count)` 쌍.
 * group-by-count 쿼리들이 공유 (case_me_too / case_watchlist 의 co-occurrence 등).
 */
interface UserCountProjection {
    val userId: Long
    val count: Long
}
