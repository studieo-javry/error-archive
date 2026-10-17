package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.demo.config.DemoProperties

/**
 * 기동 완료 시 데모 워크스페이스가 비어있으면 시드 콘텐츠를 채운다(새 배포 직후 populate).
 * iam 미기동 등으로 실패해도 기동은 막지 않으며, 야간 리셋이 보정한다.
 */
@Component
class DemoBootstrap(
    private val resetService: DemoResetService,
    private val props: DemoProperties,
) {
    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.enabled) return
        runCatching { resetService.ensureSeededOnStartup() }
            .onFailure { log.warn { "demo seed on startup deferred: ${it.message}" } }
    }
}
