package org.studieojavry.coreapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
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
    private val tagRepository: ErrorCaseTagRepositoryPort,
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
        // 게스트가 지연 생성한 개인(workspaceId=null) 케이스도 정리 — 데모 환경에선 개인 케이스=게스트 산물.
        // (iam 의 야간 deleteAllGuests 와 짝. 워크스페이스 wipe 로는 안 지워지므로 별도 purge.)
        val wipedPersonal = wipePersonalCases()
        // 시드 작성자 = persona 들(iam 미제공 시 봇으로 폴백). 케이스를 여러 작성자로 분산.
        val authorIds = id.personaUserIds.ifEmpty { listOfNotNull(id.botUserId) }
        val seeded = seeder.seedFresh(id.workspaceId, authorIds)
        log.info { "demo reset: wiped=$wiped wipedPersonal=$wipedPersonal seeded=$seeded (ws=${id.workspaceId})" }
        return ResetResult(ran = true, wiped = wiped + wipedPersonal, seeded = seeded)
    }

    /**
     * 기동 시 시드 동기화 — **콘텐츠 버전 태그**(`seed-vN`) 기준.
     *  - 봇 시드 케이스가 없거나, 샘플 케이스에 현재 버전 태그가 없으면(=개수 그대로여도 **내용이 바뀜**)
     *    → wipe+reseed. 시드를 바꾸고 SEED_VERSION 만 올려 롤아웃하면 노드 수동 트리거 없이 자동 반영.
     *  - 현재 버전이면 no-op(게스트 콘텐츠 보존, 일반 재기동에서 불필요한 wipe 방지).
     * iam 미기동 등 실패 시 조용히 넘어가고 야간 리셋이 보정.
     */
    fun syncSeedOnStartup() {
        if (!props.enabled) return
        val id = identityClient.fetchIdentity() ?: return
        if (!id.enabled || id.workspaceId == null || id.botUserId == null) return

        // 시드 소유자가 persona 로 분산되므로, 샘플은 첫 작성자 소유 케이스로 조회(버전 태그 확인용).
        val sampleOwner = id.personaUserIds.ifEmpty { listOfNotNull(id.botUserId) }.first()
        val sampleId = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = id.workspaceId, ownerUserId = sampleOwner, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = 1,
            ),
        ).firstOrNull()?.id
        val current = sampleId != null && tagRepository.findAllByErrorCaseId(sampleId).contains(seeder.versionTag)

        if (current) {
            log.info { "demo seed up to date on startup (${seeder.versionTag})" }
        } else {
            val r = reset()
            log.info { "demo seed (re)seeded on startup [${seeder.versionTag}]: wiped=${r.wiped} seeded=${r.seeded}" }
        }
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

    /**
     * 데모 게스트 개인 케이스만 purge(cascade) — **반드시 데모 태그 스코프**.
     * ⚠️ 2026-10-19 인시던트: 과거엔 workspaceId=null 전체(실사용자 개인 포함)를 지워 데이터가 유실됐다.
     *    이제 DemoGuestPersonalSeeder 가 붙인 태그(GUEST_PERSONAL_TAG)가 있는 개인 케이스만 지운다.
     */
    private fun wipePersonalCases(): Int {
        var total = 0
        repeat(MAX_WIPE_BATCHES) {
            val ids = errorCaseRepository.findDemoGuestPersonalCaseIds(
                DemoGuestPersonalSeeder.GUEST_PERSONAL_TAG, WIPE_BATCH,
            )
            if (ids.isEmpty()) return total
            ids.forEach { deleteErrorCaseUseCase.purge(it); total++ }
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
