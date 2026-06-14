package org.studieojavry.notiapi.notification.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.domain.DeviceToken

@Service
class RegisterDeviceTokenUseCase(
    private val repository: DeviceTokenRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, token: String, platformCode: String): DeviceToken {
        val platform = runCatching { DeviceToken.Platform.valueOf(platformCode.uppercase()) }
            .getOrElse { throw IllegalArgumentException("unsupported platform: $platformCode") }
        return repository.register(userId, token, platform)
    }
}

@Service
class RemoveDeviceTokenUseCase(
    private val repository: DeviceTokenRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, token: String): Boolean =
        repository.remove(userId, token) > 0
}