package org.studieojavry.iamapi.demo.application

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.demo.config.DemoProperties

/**
 * 앱 기동 완료 시 데모 봇+워크스페이스를 미리 보장해, 첫 게스트 로그인 전에 진입 대상이 준비되도록 한다.
 * 실패해도 기동은 막지 않는다(게스트 로그인/스케줄 리셋이 재시도).
 */
@Component
class DemoBootstrap(
    private val provisioner: DemoWorkspaceProvisioner,
    private val props: DemoProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.enabled) return
        runCatching { provisioner.ensureProvisioned() }
            .onSuccess { log.info("demo workspace provisioned: ws={} bot={}", it.workspaceId, it.botUserId) }
            .onFailure { log.warn("demo workspace provisioning deferred (will retry on login/schedule): {}", it.message) }
    }
}
