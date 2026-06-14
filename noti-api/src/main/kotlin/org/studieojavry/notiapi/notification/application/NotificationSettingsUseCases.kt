package org.studieojavry.notiapi.notification.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.domain.NotificationSettings

/**
 * Settings → Notifications. row 가 없으면 [NotificationSettings.defaults] 반환.
 */
@Service
class GetMyNotificationSettingsUseCase(
    private val repository: NotificationSettingsRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): NotificationSettings =
        repository.findByUserId(userId) ?: NotificationSettings.defaults()
}

/**
 * 부분 갱신 — null/미포함 필드 유지(deep merge).
 *
 * **Security alerts** 는 Patch 모델에 없음 — 강제 on 이라 변경 불가.
 *
 * row 가 없으면 *현재 기본값 + patch* 로 새 row 생성.
 */
@Service
class UpdateMyNotificationSettingsUseCase(
    private val repository: NotificationSettingsRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, patch: NotificationSettings.Patch): NotificationSettings {
        val current = repository.findByUserId(userId) ?: NotificationSettings.defaults()
        val merged = current.mergePatch(patch)
        repository.upsert(userId, merged)
        return merged
    }
}