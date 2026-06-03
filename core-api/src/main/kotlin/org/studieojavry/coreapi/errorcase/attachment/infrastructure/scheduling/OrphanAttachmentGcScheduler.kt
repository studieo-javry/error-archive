package org.studieojavry.coreapi.errorcase.attachment.infrastructure.scheduling

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.PurgeOrphanAttachmentsUseCase

/**
 * orphan 첨부 GC 트리거 (인프라 관심사 — 스케줄·락은 여기, 정리 정책은 use case).
 *
 * 기본 10분 주기. `core.attachment.orphan-gc-cron` 으로 조정.
 * `@SchedulerLock` 으로 다중 인스턴스 중 한 곳에서만 실행 — 잡 이름 단위로 락.
 *  - lockAtMostFor: 인스턴스가 죽어 락을 못 풀어도 이 시간 후 자동 해제(데드락 방지).
 *  - lockAtLeastFor: 너무 빨리 끝나도 최소 이 시간은 락 유지(시계 흔들림에 의한 중복 실행 방지).
 */
@Component
class OrphanAttachmentGcScheduler(
    private val purgeOrphanAttachments: PurgeOrphanAttachmentsUseCase,
) {

    @Scheduled(cron = "\${core.attachment.orphan-gc-cron:0 */10 * * * *}")
    @SchedulerLock(name = "purgeOrphanAttachments", lockAtMostFor = "PT9M", lockAtLeastFor = "PT30S")
    fun purge() {
        purgeOrphanAttachments.invoke()
    }
}
