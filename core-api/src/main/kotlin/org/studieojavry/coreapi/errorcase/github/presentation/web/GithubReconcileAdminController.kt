package org.studieojavry.coreapi.errorcase.github.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.errorcase.github.application.usecase.GithubEvidenceReconciler
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled

/** 놓친 웹훅 재동기화 수동 실행 — 서버 장애·웹훅 설정 오류가 풀린 직후 주기를 기다리지 않고 맞출 때. `/internal` 이라 외부 미노출. */
@Tag(name = "github-webhook-admin", description = "GitHub 웹훅 받은 편지함 운영 — internal-auth 보유 호출자만.")
@RestController
@RequestMapping("/internal/admin/github/reconcile")
@ConditionalOnGithubEnabled
class GithubReconcileAdminController(
    private val reconciler: GithubEvidenceReconciler,
) {
    @Operation(
        summary = "놓친 웹훅 재동기화 한 회차 실행",
        description = "Case 에 붙은 Issue/PR 중 열림·CI 진행 중·최근 바뀐 것을 오래 확인 안 한 순으로 다시 조회해 맞춘다(주기 실행과 같은 규칙, " +
            "같은 대상은 core.github.reconcile.min-interval 에 한 번). 호출 한도 예비분에 닿은 설치는 미룬다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "실행됨 — 맞춘·실패·건너뜀·미룬 대상 수"),
        ApiResponse(responseCode = "401", description = "internal-auth 누락", content = [Content()]),
    )
    @PostMapping
    fun run(
        @Parameter(description = "이번에 확인할 최대 대상 수 (1~500).")
        @RequestParam(defaultValue = "30") limit: Int,
    ): GithubEvidenceReconciler.Result = reconciler.reconcileDue(limit)
}
