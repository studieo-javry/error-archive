package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.StoragePolicy
import java.time.Clock
import java.time.Instant

/**
 * 프로젝트 바인딩 생성 — 웹 사용자가 projectKey(+선택 repo)를 명시적으로 등록한다.
 * 같은 (owner, projectKey) 가 이미 있으면 **그대로 반환**(멱등 — 이름/ repo 동일만으로 권한 확대 금지 원칙상
 * 재생성 대신 기존을 재사용). repo 매핑 변경은 별도 수정 유스케이스(후속).
 */
@Service
class CreateProjectBindingUseCase(
    private val bindings: ProjectBindingRepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: CreateProjectBindingCommand): Result {
        bindings.findByOwnerAndKey(cmd.ownerUserId, cmd.projectKey)?.let {
            return Result(it, created = false)
        }
        val now = Instant.now(clock)
        val saved = bindings.save(
            ProjectBinding(
                ownerUserId = cmd.ownerUserId,
                projectKey = cmd.projectKey,
                normalizedRepoId = cmd.normalizedRepoId?.takeIf { it.isNotBlank() },
                storagePolicy = StoragePolicy.DEFAULT,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return Result(saved, created = true)
    }

    data class Result(val binding: ProjectBinding, val created: Boolean)
}

data class CreateProjectBindingCommand(
    val ownerUserId: Long,
    val projectKey: String,
    val normalizedRepoId: String? = null,
)

/** 소유자의 프로젝트 바인딩 목록(projectKey 순). */
@Service
class ListProjectBindingsUseCase(
    private val bindings: ProjectBindingRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long): List<ProjectBinding> = bindings.listByOwner(ownerUserId)
}
