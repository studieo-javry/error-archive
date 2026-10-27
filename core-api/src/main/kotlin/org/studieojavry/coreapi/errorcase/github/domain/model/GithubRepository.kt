package org.studieojavry.coreapi.errorcase.github.domain.model

import java.time.Instant

/**
 * 설치 대상 레포. **id 로 식별**(full_name 은 rename/transfer 로 바뀜).
 *
 * [isPrivate] 은 근거 노출 정책(비공개 레포 근거는 외부인에게 가림)의 기준이라
 * `repository`(privatized/publicized) 웹훅으로 최신을 유지한다.
 * [sourceUpdatedAt] — GitHub 쪽 `updated_at`. 늦게 도착한 옛 이벤트가 최신 값을 덮지 않게 비교.
 */
class GithubRepository private constructor(
    val repoId: Long,
    var installationId: Long,
    var fullName: String,
    var isPrivate: Boolean,
    var removedAt: Instant?,
    var sourceUpdatedAt: Instant?,
) {
    val isActive: Boolean get() = removedAt == null

    /** 설치 대상으로 (다시) 들어옴. 다른 설치로 옮겨진 경우(transfer)도 여기서 갱신. */
    fun attach(installationId: Long, fullName: String, isPrivate: Boolean) {
        this.installationId = installationId
        this.fullName = fullName
        this.isPrivate = isPrivate
        this.removedAt = null
    }

    fun detach(at: Instant = Instant.now()) {
        if (removedAt == null) removedAt = at
    }

    /**
     * `repository` 웹훅 반영. [updatedAt] 이 이미 반영한 값보다 오래됐으면 무시(false).
     * 웹훅은 순서 보장이 없어 privatized → publicized 가 뒤집혀 도착할 수 있다.
     */
    fun applyChange(fullName: String, isPrivate: Boolean, updatedAt: Instant?): Boolean {
        val last = sourceUpdatedAt
        if (updatedAt != null && last != null && updatedAt.isBefore(last)) return false
        this.fullName = fullName
        this.isPrivate = isPrivate
        if (updatedAt != null) this.sourceUpdatedAt = updatedAt
        return true
    }

    companion object {
        fun of(repoId: Long, installationId: Long, fullName: String, isPrivate: Boolean) =
            GithubRepository(repoId, installationId, fullName, isPrivate, null, null)

        fun reconstitute(
            repoId: Long,
            installationId: Long,
            fullName: String,
            isPrivate: Boolean,
            removedAt: Instant?,
            sourceUpdatedAt: Instant?,
        ) = GithubRepository(repoId, installationId, fullName, isPrivate, removedAt, sourceUpdatedAt)
    }
}
