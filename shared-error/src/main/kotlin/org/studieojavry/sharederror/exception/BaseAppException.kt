package org.studieojavry.sharederror.exception

import org.springframework.http.HttpStatus

/**
 * 도메인 비즈니스 예외의 base class.
 *
 * 핸들러가 *예외 자체에서* code / status / retryable 을 추출하므로 advice 안에서
 * type 별 매핑이 1줄로 줄어든다.
 */
abstract class BaseAppException(
    val code: String,
    val status: HttpStatus,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
