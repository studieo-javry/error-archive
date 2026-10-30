package org.studieojavry.coreapi.errorcase.case.application.usecase

/**
 * draft-suggestion 사용자별 쿼터/버스트 초과 — HTTP 429 로 매핑(핸들러에서 Retry-After 헤더 포함).
 */
class DraftRateLimitExceededException(
    val retryAfterSeconds: Long,
    val scope: String,
) : RuntimeException("draft-suggestion rate limit exceeded ($scope), retry after ${retryAfterSeconds}s")
