package org.studieojavry.iamapi.auth.domain.model.exception

import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus

/**
 * 사용자가 sign-in 불가능한 상태일 때 throw.
 *
 * 의미: 토큰은 valid 하지만 *user 상태가 invalid*.
 * InvalidRefreshTokenException(토큰 자체의 invalidity)과 의미 분리.
 *
 * advice 가 [userStatus] 를 보고 code/status 결정:
 *  - DELETED   → 410 Gone, code=ACCOUNT_DELETED (영구 삭제)
 *  - (확장)    → 그 외 비활성 상태는 403, code=ACCOUNT_NOT_ACTIVE
 */
class AccountNotActiveException(val userStatus: UserStatus) :
    RuntimeException("user is not allowed to sign in: status=$userStatus")
