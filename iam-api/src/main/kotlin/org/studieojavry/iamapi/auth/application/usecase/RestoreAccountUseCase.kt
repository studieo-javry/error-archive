package org.studieojavry.iamapi.auth.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus

/**
 * 회원 탈퇴 취소(복구) — PENDING_DELETION 상태에서 ACTIVE 로 복귀.
 *
 * 워크스페이스 멤버십·팔로우는 이미 삭제됐으므로 **복원하지 않는다** — 사용자가 다시 가입·팔로우 필요.
 * 이건 의도된 단순화: 멤버십을 보관·복원 처리하면 복구 로직이 폭발한다.
 */
@Service
class RestoreAccountUseCase(
    private val userRepository: UserRepositoryPort,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun invoke(userId: Long): Result {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        if (user.status == UserStatus.ACTIVE) return Result(alreadyActive = true)
        if (user.status != UserStatus.PENDING_DELETION) {
            throw IllegalStateException("only PENDING_DELETION users can be restored: current=${user.status}")
        }

        user.restore()
        userRepository.save(user)
        log.info { "[account] user=$userId restored (PENDING_DELETION → ACTIVE)" }
        return Result(alreadyActive = false)
    }

    data class Result(val alreadyActive: Boolean)
}
