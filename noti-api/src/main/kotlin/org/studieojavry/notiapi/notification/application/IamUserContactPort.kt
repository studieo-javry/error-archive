package org.studieojavry.notiapi.notification.application

/**
 * iam-api 의 `/internal/users/{id}/contact` 호출 추상화.
 * 실패/타임아웃 시 null 반환 — 발송 채널은 graceful skip 으로 처리.
 */
interface IamUserContactPort {
    fun fetch(userId: Long): UserContact?

    data class UserContact(
        val userId: Long,
        val email: String?,
        val displayName: String,
        val active: Boolean,
    )
}
