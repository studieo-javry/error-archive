package org.studieojavry.coreapi.errorcase.github.presentation.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import org.studieojavry.coreapi.errorcase.github.application.usecase.ResolveGithubReferencesUseCase
import org.studieojavry.coreapi.errorcase.github.application.usecase.SearchGithubReferencesUseCase
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository

@Schema(description = "본문 # 자동완성이 찾을 수 있는 레포 — 공간에 연결된 활성 레포")
data class GithubReferenceRepositoriesResponse(
    @field:Schema(description = "레포 이름순. 비어 있으면 자동완성을 띄우지 않는다(# 는 일반 텍스트)")
    val repositories: List<GithubRepositoryResponse>,
) {
    companion object {
        fun of(repos: List<GithubRepository>) = GithubReferenceRepositoriesResponse(repos.map(GithubRepositoryResponse::of))
    }
}

@Schema(description = "본문 # 자동완성 검색 결과")
data class GithubReferenceSearchResponse(
    @field:Schema(description = "레포 이름 → 번호 순, 최대 8개") val items: List<GithubReferenceSearchItemResponse>,
) {
    companion object {
        fun of(hits: List<SearchGithubReferencesUseCase.Hit>) = GithubReferenceSearchResponse(
            hits.map { GithubReferenceSearchItemResponse(it.repo, it.number, it.kind, it.title, it.status) },
        )
    }
}

@Schema(description = "검색 결과 한 줄. 고르면 본문에 `{repo}#{number}` 를 넣는다")
data class GithubReferenceSearchItemResponse(
    @field:Schema(example = "acme/payment-api") val repo: String,
    @field:Schema(example = "95") val number: Int,
    @field:Schema(description = "ISSUE | PULL_REQUEST") val kind: String,
    val title: String,
    @field:Schema(description = "OPEN | CLOSED | MERGED(PR)") val status: String,
)

@Schema(description = "본문 GitHub 참조 조회 요청")
data class ResolveGithubReferencesRequest(
    @field:Schema(description = "본문에서 찾은 `owner/repo#N` 목록(중복은 하나로, 최대 50개 처리)", example = "[\"acme/payment-api#95\"]")
    @field:Size(max = 200)
    val refs: List<String> = emptyList(),
)

@Schema(description = "본문 GitHub 참조 조회 결과")
data class GithubReferencesResponse(val references: List<GithubReferenceResponse>) {
    companion object {
        fun of(refs: List<ResolveGithubReferencesUseCase.Reference>) = GithubReferencesResponse(
            refs.map { GithubReferenceResponse(it.ref, it.repo, it.number, it.resolved, it.masked, it.kind, it.title, it.status, it.url) },
        )
    }
}

@Schema(description = "참조 하나. resolved=false 면 kind·title·status·url 없음")
data class GithubReferenceResponse(
    @field:Schema(description = "요청한 참조 그대로", example = "acme/payment-api#95") val ref: String,
    @field:Schema(description = "레포(연결돼 있으면 현재 이름)") val repo: String,
    val number: Int,
    @field:Schema(description = "제목·상태를 가져왔는지") val resolved: Boolean,
    @field:Schema(description = "비공개 레포라 요청자에게 가림(제목·상태 없음)") val masked: Boolean,
    @field:Schema(description = "ISSUE | PULL_REQUEST") val kind: String?,
    val title: String?,
    @field:Schema(description = "OPEN | CLOSED | MERGED(PR)") val status: String?,
    val url: String?,
)
