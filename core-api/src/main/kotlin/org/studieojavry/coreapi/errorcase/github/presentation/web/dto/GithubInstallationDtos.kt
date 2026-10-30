package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.studieojavry.coreapi.errorcase.github.application.usecase.ListMyGithubInstallationsUseCase
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.shared.presentation.toApiOffset
import java.time.OffsetDateTime

@Schema(description = "GitHub App 설치 페이지 URL")
data class GithubInstallUrlResponse(
    @field:Schema(description = "브라우저를 이 URL 로 이동시킨다. 설치 후 GitHub 가 App 의 Setup URL(FE)로 installation_id·code·state 를 붙여 돌려보낸다.")
    val url: String,
)

@Schema(description = "설치 완료 요청 — FE 가 GitHub 에서 돌아온 쿼리 파라미터를 그대로 전달")
data class CompleteGithubInstallationRequest(
    @field:Schema(description = "GitHub installation id (`installation_id` 쿼리)", example = "12345678")
    @field:Positive
    val installationId: Long,
    @field:Schema(description = "설치 시 사용자 인가 코드 (`code` 쿼리) — 설치 소유 검증에만 쓰고 저장하지 않음")
    @field:NotBlank
    val code: String,
    @field:Schema(description = "install-url 발급 때 받은 서명된 state (`state` 쿼리)")
    @field:NotBlank
    val state: String,
)

@Schema(description = "연결된 GitHub 설치")
data class GithubInstallationResponse(
    @field:Schema(description = "GitHub installation id") val installationId: Long,
    @field:Schema(description = "GitHub 계정(org/user) login", example = "studieo-javry") val accountLogin: String,
    @field:Schema(description = "Organization | User") val accountType: String,
    @field:Schema(description = "연결 범위 워크스페이스. null = 개인 연결") val workspaceId: Long?,
    @field:Schema(description = "연결한 사용자") val installedByUserId: Long,
    @field:Schema(description = "ACTIVE | SUSPENDED") val status: String,
    @field:Schema(description = "설치 대상 레포(활성)") val repositories: List<GithubRepositoryResponse>,
    val connectedAt: OffsetDateTime,
) {
    companion object {
        fun of(installation: GithubInstallation, repositories: List<GithubRepository>) = GithubInstallationResponse(
            installationId = installation.installationId,
            accountLogin = installation.accountLogin,
            accountType = installation.accountType,
            workspaceId = installation.workspaceId,
            installedByUserId = installation.installedByUserId,
            status = installation.status.name,
            repositories = repositories.map(GithubRepositoryResponse::of),
            connectedAt = installation.createdAt.toApiOffset(),
        )

        fun of(item: ListMyGithubInstallationsUseCase.Item) = of(item.installation, item.repositories)
    }
}

@Schema(description = "설치 대상 레포")
data class GithubRepositoryResponse(
    @field:Schema(description = "GitHub repo id (rename 에도 불변)") val repoId: Long,
    @field:Schema(example = "studieo-javry/error-archive") val fullName: String,
    @field:Schema(description = "비공개 레포 — 이 레포에서 온 근거는 외부인에게 가려진다") val isPrivate: Boolean,
) {
    companion object {
        fun of(r: GithubRepository) = GithubRepositoryResponse(r.repoId, r.fullName, r.isPrivate)
    }
}
