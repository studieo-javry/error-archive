package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import java.time.LocalDateTime

/**
 * GitHub App 설치 1건 ↔ Error Archive 연결. `installationId` 는 GitHub 쪽 id(불변).
 *
 * 연결 주체는 [installedByUserId], 범위는 [workspaceId](null = 개인 연결).
 * 이 매핑이 "어느 레포 이벤트가 어느 워크스페이스 Case 에 붙을 수 있나" 의 기준이 된다.
 */
class GithubInstallation private constructor(
    val installationId: Long,
    var accountLogin: String,
    var accountType: String,
    var workspaceId: Long?,
    var installedByUserId: Long,
    var status: GithubInstallationStatus,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime,
) {
    /** 같은 연결 주체(사용자+워크스페이스)인가 — 다른 주체가 이미 쥔 설치를 가로채지 못하게 비교. */
    fun isBoundTo(userId: Long, workspaceId: Long?): Boolean =
        installedByUserId == userId && this.workspaceId == workspaceId

    /** 재연결(설치 화면을 다시 거침). 삭제됐던 설치가 같은 id 로 돌아오는 경우는 없지만 ACTIVE 로 복구해 둔다. */
    fun rebind(userId: Long, workspaceId: Long?, accountLogin: String, accountType: String) {
        this.installedByUserId = userId
        this.workspaceId = workspaceId
        this.accountLogin = accountLogin
        this.accountType = accountType
        this.status = GithubInstallationStatus.ACTIVE
        touch()
    }

    fun changeStatus(next: GithubInstallationStatus) {
        if (status == next) return
        // DELETED 는 종착 — 이후 suspend/unsuspend 웹훅이 늦게 와도 되살리지 않는다.
        if (status == GithubInstallationStatus.DELETED) return
        status = next
        touch()
    }

    private fun touch() {
        updatedAt = LocalDateTime.now()
    }

    companion object {
        fun connect(
            installationId: Long,
            accountLogin: String,
            accountType: String,
            workspaceId: Long?,
            installedByUserId: Long,
        ): GithubInstallation {
            val now = LocalDateTime.now()
            return GithubInstallation(
                installationId, accountLogin, accountType, workspaceId, installedByUserId,
                GithubInstallationStatus.ACTIVE, now, now,
            )
        }

        fun reconstitute(
            installationId: Long,
            accountLogin: String,
            accountType: String,
            workspaceId: Long?,
            installedByUserId: Long,
            status: GithubInstallationStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
        ) = GithubInstallation(
            installationId, accountLogin, accountType, workspaceId, installedByUserId, status, createdAt, updatedAt,
        )
    }
}
