package org.studieojavry.coreapi.errorcase.snippet.infrastructure.scheduling

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.scheduling.OrphanAttachmentGcScheduler
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.PurgeOrphanSnippetsUseCase


/**
 * orphan 스니펫 GC 트리거 (첨부 OrphanAttachmentGcScheduler 와 대칭).
 * 기본 10분 주기, `core.snippet.orphan-gc-cron` 으로 조정. 잡 이름 단위 분산 락.
 */
@Component
class OrphanSnippetGcScheduler(
    private val purgeOrphanSnippets: PurgeOrphanSnippetsUseCase,
) {

    @Scheduled(cron = "\${core.snippet.orphan-gc-cron:0 */10 * * * *}")
    @SchedulerLock(name = "purgeOrphanSnippets", lockAtMostFor = "PT9M", lockAtLeastFor = "PT30S")
    fun purge() {
        purgeOrphanSnippets.invoke()
    }
}
