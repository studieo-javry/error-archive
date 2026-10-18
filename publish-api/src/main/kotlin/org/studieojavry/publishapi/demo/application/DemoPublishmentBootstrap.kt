package org.studieojavry.publishapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.demo.config.DemoPublishmentProperties
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort

/**
 * 데모 발행물 라이프사이클 오케스트레이션 (demo.enabled 일 때만).
 *
 *  - 부팅 완료 시: 워크스페이스 데모 발행물 10건 멱등 시드.
 *  - 야간(기본 04:30 KST, core 리셋 04:10 이후): 게스트 1회용 데모 발행물 purge → 워크스페이스 재시드(누락분 복구).
 *    워크스페이스 10건은 예약 owner 라 purge 대상이 아니며, 재시드는 멱등이라 slug 안정.
 *
 * 모든 진입점은 best-effort(runCatching) — 데모 보조 로직이 서비스 기동/운영을 막지 않게.
 */
@Component
class DemoPublishmentBootstrap(
    private val props: DemoPublishmentProperties,
    private val workspaceSeeder: DemoWorkspacePublishmentSeeder,
    private val repository: CasePublishmentRepositoryPort,
) {
    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.enabled) return
        runCatching { workspaceSeeder.seed() }
            .onFailure { log.warn(it) { "demo workspace publishment seed on startup failed" } }
    }

    @Scheduled(cron = "\${demo.purge-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "demo-publishment-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    fun nightlyPurgeAndReseed() {
        if (!props.enabled) return
        // 게스트 1회용 데모 발행물만 수거(예약 owner=워크스페이스분은 보존).
        val purged = repository.purgeGuestDemo(DemoWorkspacePublishmentSeeder.DEMO_WORKSPACE_OWNER_ID)
        val reseeded = workspaceSeeder.seed()
        log.info { "demo publishment nightly: purged $purged guest rows, workspace reseeded $reseeded" }
    }
}
