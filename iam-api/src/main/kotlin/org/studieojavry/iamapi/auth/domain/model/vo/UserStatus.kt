package org.studieojavry.iamapi.auth.domain.model.vo

enum class UserStatus {
    ACTIVE,
    /** 사용자가 탈퇴 요청한 grace period 중. 본인은 로그인 가능(복구 결정). 만료 시 DELETED 로 전환. */
    PENDING_DELETION,
    SUSPENDED,
    DELETED;

    /** PENDING_DELETION 도 로그인 허용 — 사용자가 복구/탈퇴 확정 결정을 내릴 기회. */
    fun canSignIn(): Boolean = this == ACTIVE || this == PENDING_DELETION
}
