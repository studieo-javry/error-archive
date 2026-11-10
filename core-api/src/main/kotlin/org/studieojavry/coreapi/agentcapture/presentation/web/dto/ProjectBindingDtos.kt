package org.studieojavry.coreapi.agentcapture.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import java.time.Instant

@Schema(description = "프로젝트 바인딩 생성 요청. 같은 projectKey 가 이미 있으면 기존을 반환한다(멱등).")
data class CreateProjectBindingRequest(
    @field:NotBlank
    @Schema(description = "사용자가 정한 프로젝트 식별자(로컬 경로와의 매핑은 커넥터가 설정).")
    val projectKey: String,
    @Schema(description = "정규화한 repo 식별자(GitHub 연결 시). 소유권·접근은 별도 검증 — 이름만으로 권한 확대 X.")
    val normalizedRepoId: String? = null,
)

@Schema(description = "프로젝트 바인딩.")
data class ProjectBindingResponse(
    val id: Long,
    val projectKey: String,
    val normalizedRepoId: String?,
    val storagePolicy: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(b: ProjectBinding) = ProjectBindingResponse(
            id = requireNotNull(b.id),
            projectKey = b.projectKey,
            normalizedRepoId = b.normalizedRepoId,
            storagePolicy = b.storagePolicy.name,
            createdAt = b.createdAt,
        )
    }
}

@Schema(description = "소유자의 프로젝트 바인딩 목록.")
data class ProjectBindingListResponse(
    val items: List<ProjectBindingResponse>,
)
