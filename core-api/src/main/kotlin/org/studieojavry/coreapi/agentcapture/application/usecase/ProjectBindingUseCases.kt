package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaseNotOwnedException
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBindingNotFoundException
import org.studieojavry.coreapi.agentcapture.domain.model.StoragePolicy
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
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

/**
 * 이미 만들어진 Case 를 ProjectBinding 에 사후 귀속/해제 — 웹 세션 사용자(본인 소유)만.
 *
 * #162 는 import(검토 반영) 시점에만 신규 Case 를 귀속시켰다. 바인딩 도입 전에 만든 Case,
 * 또는 바인딩 없이 반영한 Case(project_binding_id = null)는 에이전트 case:read(IS NOT NULL 필터)에
 * 영영 안 보였다 → 사용자가 과거 Case 를 프로젝트에 명시 연결할 수 있게 한다.
 *
 * 존재 노출 회피: 대상 Case 가 없거나 타인 소유면 404(CaseNotOwned), 바인딩이 없거나 타인 소유면
 * 404(ProjectBindingNotFound). 둘 다 본인 소유여야 귀속 가능(이름/repo 동일만으로 확대 금지 원칙).
 */
@Service
class AssignCaseProjectBindingUseCase(
    private val errorCases: ErrorCaseRepositoryPort,
    private val bindings: ProjectBindingRepositoryPort,
) {
    /** 기존 Case 를 지정 ProjectBinding 에 귀속. */
    @Transactional
    fun bind(userId: Long, caseId: Long, projectBindingId: Long) {
        requireOwnedCase(userId, caseId)
        val binding = bindings.findById(projectBindingId)
        if (binding == null || binding.ownerUserId != userId) throw ProjectBindingNotFoundException(projectBindingId)
        errorCases.updateProjectBinding(caseId, projectBindingId)
    }

    /** 기존 Case 의 귀속 해제(미귀속으로 — 에이전트 case:read 대상에서 제외). */
    @Transactional
    fun unbind(userId: Long, caseId: Long) {
        requireOwnedCase(userId, caseId)
        errorCases.updateProjectBinding(caseId, null)
    }

    private fun requireOwnedCase(userId: Long, caseId: Long) {
        val ownerId = errorCases.findOwnerUserIdById(caseId)
        if (ownerId == null || ownerId != userId) throw CaseNotOwnedException(caseId)
    }
}
