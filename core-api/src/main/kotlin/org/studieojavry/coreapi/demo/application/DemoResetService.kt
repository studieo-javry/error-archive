package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.usecase.DeleteErrorCaseUseCase

/**
 * 데모 워크스페이스 콘텐츠 리셋 — 야간 cron + 수동 트리거 공용.
 *
 * 전략: 데모 WS 의 **모든 케이스를 wipe → 봇 시드 재생성**(drop & reseed, 멱등). 게스트가 만든 콘텐츠는
 * 작성자 무관하게 WS 단위로 함께 사라진다. iam 의 게스트 사용자 정리와 짝을 이뤄 매일 베이스라인으로 복귀.
 * (데모 WS/봇 ID 는 iam `/internal/demo/identity` 로 조회.)
 */
@Service
class DemoResetService(
    private val props: DemoProperties,
    private val identityClient: DemoIamIdentityClient,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val deleteErrorCaseUseCase: DeleteErrorCaseUseCase,
    private val seeder: DemoSeeder,
) {
    private val log = KotlinLogging.logger {}

    fun reset(): ResetResult {
        if (!props.enabled) return ResetResult(ran = false, wiped = 0, seeded = 0)
        val id = identityClient.fetchIdentity()
        if (id == null || !id.enabled || id.workspaceId == null || id.botUserId == null) {
            log.warn { "demo reset skipped — identity unavailable/disabled" }
            return ResetResult(ran = false, wiped = 0, seeded = 0)
        }
        val wiped = wipeWorkspace(id.workspaceId)
        val seeded = seeder.seedFresh(id.workspaceId, id.botUserId)
        log.info { "demo reset: wiped=$wiped seeded=$seeded (ws=${id.workspaceId})" }
        return ResetResult(ran = true, wiped = wiped, seeded = seeded)
    }

    /** 기동 시 비어있으면 populate(파괴 없음). iam 미기동 등으로 실패하면 조용히 넘어가고 야간 리셋이 보정. */
    fun ensureSeededOnStartup() {
        if (!props.enabled) return
        val id = identityClient.fetchIdentity() ?: return
        if (!id.enabled || id.workspaceId == null || id.botUserId == null) return
        val n = seeder.ensureSeeded(id.workspaceId, id.botUserId)
        if (n > 0) log.info { "demo seed on startup: created=$n (ws=${id.workspaceId})" }
    }

    private fun wipeWorkspace(workspaceId: Long): Int {
        var total = 0
        repeat(MAX_WIPE_BATCHES) {
            val batch = errorCaseRepository.search(
                ErrorCaseSearchCriteria(
                    workspaceId = workspaceId,
                    ownerUserId = null,
                    status = null,
                    fingerprint = null,
                    visibility = null,
                    cursorCreatedAt = null,
                    cursorId = null,
                    limit = WIPE_BATCH,
                ),
            )
            if (batch.isEmpty()) return total
            batch.forEach { deleteErrorCaseUseCase.purge(it.id); total++ }
        }
        return total
    }

    @Scheduled(cron = "\${demo.reset-cron}", zone = "Asia/Seoul")
    fun scheduledReset() {
        if (props.enabled) reset()
    }

    data class ResetResult(val ran: Boolean, val wiped: Int, val seeded: Int)

    companion object {
        private const val WIPE_BATCH = 200
        private const val MAX_WIPE_BATCHES = 200   // 최대 ~40k 케이스 안전 상한
    }
}
