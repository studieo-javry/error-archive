package org.studieojavry.publishapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.publishapi.demo.config.DemoPublishmentProperties

/**
 * 데모 발행물 라이프사이클 오케스트레이션 (demo.enabled 일 때만).
 *
 *  - 부팅 완료 시: 게스트 1회용 데모 발행물 purge → 워크스페이스 데모 발행물 10건 멱등 시드.
 *    (배포마다 공개 Explore 가 워크스페이스분으로 정돈됨 — 야간 purge 를 기다리지 않아도 됨.)
 *  - 야간(기본 04:30 KST, core 리셋 04:10 이후): 동일(게스트 purge → 워크스페이스 재시드).
 *    워크스페이스 10건은 예약 owner 라 purge 대상이 아니며, 재시드는 멱등이라 slug 안정.
 *    게스트 발행물은 다음 /me 진입 시 UNLISTED 로 다시 지연 시드된다.
 *
 * 모든 진입점은 best-effort(runCatching) — 데모 보조 로직이 서비스 기동/운영을 막지 않게.
 */
@Component
class DemoPublishmentBootstrap(
    private val props: DemoPublishmentProperties,
    private val workspaceSeeder: DemoWorkspacePublishmentSeeder,
) {
    private val log = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.enabled) return
        // 크로스빈 호출(workspaceSeeder) — 각 메서드가 @Transactional 이라 프록시로 트랜잭션 적용.
        runCatching {
            val purged = workspaceSeeder.purgeGuestDemo()
            val seeded = workspaceSeeder.seed()
            log.info { "demo publishment startup: purged $purged guest rows, workspace seeded $seeded" }
        }.onFailure { log.warn(it) { "demo publishment startup failed" } }
    }

    @Scheduled(cron = "\${demo.purge-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "demo-publishment-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun nightlyPurgeAndReseed() {
        if (!props.enabled) return
        val purged = workspaceSeeder.purgeGuestDemo()
        val reseeded = workspaceSeeder.seed()
        log.info { "demo publishment nightly: purged $purged guest rows, workspace reseeded $reseeded" }
    }
}
