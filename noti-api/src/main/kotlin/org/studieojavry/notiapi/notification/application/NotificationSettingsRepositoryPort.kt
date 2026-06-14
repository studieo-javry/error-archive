package org.studieojavry.notiapi.notification.application

import org.studieojavry.notiapi.notification.domain.NotificationSettings

/**
 * 사용자의 알림 설정 저장소.
 *
 * - `findByUserId`: 없으면 null. UseCase 는 응답 시 [NotificationSettings.defaults] 채움.
 * - `upsert`: row 없으면 새로 생성, 있으면 갱신. 호출자 = `applyPatch` 한 결과.
 */
interface NotificationSettingsRepositoryPort {
    fun findByUserId(userId: Long): NotificationSettings?
    fun upsert(userId: Long, settings: NotificationSettings)
}