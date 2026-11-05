package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import java.time.LocalDateTime

/**
 * GitHub App 설치 1건 = GitHub 쪽 사실(계정·상태)만 담는다. `installationId` 는 GitHub id(불변).
 *
 * 범위(개인/워크스페이스)와 레포 선택은 더 이상 여기 있지 않다 — [GithubConnection] + 연결-레포 조인이 담당.
 * [installedByUserId] 는 "GitHub 에 처음 설치한 사람" 감사용으로만 남긴다.
 */
class GithubInstallation private constructor(
    val installationId: Long,
    var accountLogin: String,
    var accountType: String,
    var installedByUserId: Long,
    var status: GithubInstallationStatus,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime,
) {
    /** 설치 화면을 다시 거쳐 들어옴 — 계정 표기 갱신 + ACTIVE 복구(연결/범위는 connection 에서 따로 처리). */
    fun refreshAccount(installedByUserId: Long, accountLogin: String, accountType: String) {
        this.installedByUserId = installedByUserId
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
            installedByUserId: Long,
        ): GithubInstallation {
            val now = LocalDateTime.now()
            return GithubInstallation(
                installationId, accountLogin, accountType, installedByUserId,
                GithubInstallationStatus.ACTIVE, now, now,
            )
        }

        fun reconstitute(
            installationId: Long,
            accountLogin: String,
            accountType: String,
            installedByUserId: Long,
            status: GithubInstallationStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
        ) = GithubInstallation(
            installationId, accountLogin, accountType, installedByUserId, status, createdAt, updatedAt,
        )
    }
}
