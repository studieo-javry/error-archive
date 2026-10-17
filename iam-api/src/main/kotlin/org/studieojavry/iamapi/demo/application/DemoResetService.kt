package org.studieojavry.iamapi.demo.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.demo.config.DemoProperties
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort

/**
 * 게스트("로그인 없이 둘러보기") 정리 — 야간 cron + 수동 트리거 공용.
 * 임시 게스트 사용자와 그 데모 워크스페이스 멤버십을 삭제한다. 데모 봇/워크스페이스/시드는 보존.
 * (core-api 의 콘텐츠 wipe+reseed 는 별도 — 각 서비스가 자기 DB 를 self-schedule 로 정리.)
 */
@Service
class DemoResetService(
    private val userRepository: UserRepositoryPort,
    private val memberRepository: WorkspaceMemberRepositoryPort,
    private val provisioner: DemoWorkspaceProvisioner,
    private val props: DemoProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun resetGuests(): Int {
        if (!props.enabled) return 0
        val demo = provisioner.ensureProvisioned()
        // 1) 게스트 멤버십 제거(데모 WS). 2) 게스트 사용자 벌크 삭제(단일 DELETE).
        userRepository.findGuests(props.maxGuestsPerReset).forEach { g ->
            g.id?.let { memberRepository.delete(demo.workspaceId, it) }
        }
        return userRepository.deleteAllGuests()
    }

    @Scheduled(cron = "\${demo.reset-cron}", zone = "Asia/Seoul")
    fun scheduledReset() {
        if (!props.enabled) return
        val removed = resetGuests()
        log.info("demo guest reset: removed {} guest users", removed)
    }
}
