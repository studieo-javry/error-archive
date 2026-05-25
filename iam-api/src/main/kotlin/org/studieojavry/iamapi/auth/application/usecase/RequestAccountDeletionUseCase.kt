package org.studieojavry.iamapi.auth.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.RefreshTokenRepositoryPort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.social.application.port.FollowRepositoryPort
import org.studieojavry.iamapi.workspace.application.usecase.TransferMembershipsOnUserLeaveUseCase
import java.time.Instant

/**
 * 회원 탈퇴 요청 — 본인만 호출. status=ACTIVE → PENDING_DELETION 로 전환하고 grace period 시작.
 *
 * 단일 트랜잭션 내에서:
 *  1) User.requestDeletion()
 *  2) 모든 워크스페이스 멤버십 정리(자동 강등 포함) — TransferMembershipsOnUserLeaveUseCase
 *  3) 팔로우 양방향 엣지 삭제
 *  4) refresh 토큰 전부 폐기 → 모든 세션 즉시 만료
 *
 * core-api 의 owner/uploader 식별자는 그대로 둔다(ghost). 프런트가 status=DELETED 를 보고 표시.
 * grace 기간(30일) 안에 같은 사용자가 다시 로그인하면 RestoreAccountUseCase 로 ACTIVE 복귀 가능.
 * 만료된 PENDING_DELETION 은 FinalizeDeletedAccountsUseCase 가 DELETED + PII 익명화 + OAuth identity 삭제.
 */
@Service
class RequestAccountDeletionUseCase(
    private val userRepository: UserRepositoryPort,
    private val refreshTokens: RefreshTokenRepositoryPort,
    private val follows: FollowRepositoryPort,
    private val transferMemberships: TransferMembershipsOnUserLeaveUseCase,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun invoke(userId: Long): Result {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        if (user.status.name == "PENDING_DELETION") return Result(alreadyPending = true, user.pendingDeletionAt!!)
        if (user.status.name != "ACTIVE") {
            throw IllegalStateException("only ACTIVE users can request deletion: current=${user.status}")
        }

        val now = Instant.now()
        // 정리 작업 먼저(bulk @Modifying + clearAutomatically=true 가 persistence context 를 비우므로
        // userRepository.save 는 **반드시 마지막**에 호출해야 UPDATE 가 누락되지 않는다).
        transferMemberships.handleLeaveAll(userId)
        follows.deleteAllByUser(userId)
        refreshTokens.revokeAllByUserId(userId)

        user.requestDeletion(now)
        userRepository.save(user)

        log.info { "[account] user=$userId moved to PENDING_DELETION at=$now" }
        return Result(alreadyPending = false, now)
    }

    data class Result(val alreadyPending: Boolean, val pendingDeletionAt: Instant)
}

/** 사용자 조회 실패. → 404. */
class UserNotFoundException(val userId: Long) : RuntimeException("user not found: $userId")
