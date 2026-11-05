package org.studieojavry.coreapi.errorcase.github.application.usecase

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubAppApiPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubInstallationRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository

/**
 * GitHub "사실"(설치·레포)을 로컬에 맞추는 공통 upsert — installations/new 완료([CompleteGithubInstallationUseCase])와
 * OAuth 연결 생성([CreateGithubConnectionUseCase])이 공유한다. 공간/연결 개념은 모른다(그건 connection 계층).
 *
 * 호출부의 짧은 트랜잭션 안에서 쓰인다(GitHub 호출은 트랜잭션 밖에서 미리 끝내 둔다).
 */
@Component
@ConditionalOnGithubEnabled
class GithubInstallationSyncer(
    private val repository: GithubInstallationRepositoryPort,
) {
    /** 설치 사실 upsert(계정 로그인/타입 최신화, 없으면 새로 연결). */
    fun upsertInstallation(userId: Long, info: GithubAppApiPort.InstallationInfo): GithubInstallation {
        val existing = repository.findById(info.id)
        val installation = existing
            ?.also { it.refreshAccount(userId, info.accountLogin, info.accountType) }
            ?: GithubInstallation.connect(info.id, info.accountLogin, info.accountType, userId)
        return repository.save(installation)
    }

    /** 설치 대상 레포를 GitHub 기준으로 맞춘다 — 목록에 없는 기존 레포는 지우지 않고 removed 표시. 반환은 현재 grant 된(활성) 레포. */
    fun syncRepositories(
        installationId: Long,
        current: List<GithubAppApiPort.RepositoryInfo>,
    ): List<GithubRepository> {
        val known = repository.findRepositories(listOf(installationId)).associateBy { it.repoId }
        val currentIds = current.map { it.id }.toSet()
        val upserts = current.map { r ->
            (known[r.id] ?: repository.findRepository(r.id))
                ?.also { it.attach(installationId, r.fullName, r.isPrivate) }
                ?: GithubRepository.of(r.id, installationId, r.fullName, r.isPrivate)
        }
        val removed = known.values.filter { it.repoId !in currentIds }.onEach { it.detach() }
        repository.saveRepositories(upserts + removed)
        return upserts
    }
}
