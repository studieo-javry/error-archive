package org.studieojavry.coreapi.demo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.demo.application.DemoGuideService
import org.studieojavry.coreapi.demo.presentation.dto.DemoGuideResponse

/**
 * FE 게스트 사용 가이드가 따라갈 샘플 케이스 + 4단계 공개 아티클. 인증 사용자(게스트 포함) 누구나 조회.
 * 데모 비활성이거나 시드가 아직 없으면 404 — FE 는 이 경우 아티클 단계 없이 진행한다.
 */
@Tag(name = "demo", description = "게스트 데모 — 사용 가이드 대상 조회")
@RestController
@RequestMapping("/api/v1/demo")
class DemoGuideController(
    private val guideService: DemoGuideService,
) {

    @Operation(
        summary = "사용 가이드 대상 조회",
        description = "가이드가 따라갈 데모 샘플 케이스 id(야간 리셋마다 바뀜)와, 같은 주제로 발행된 공개 아티클 slug 를 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "샘플 케이스 id + 아티클 slug"),
        ApiResponse(responseCode = "404", description = "데모 비활성 또는 시드 미존재", content = [Content()]),
    )
    @GetMapping("/guide")
    fun guide(): DemoGuideResponse {
        val g = guideService.find() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "demo guide unavailable")
        return DemoGuideResponse(caseId = g.caseId, articleSlug = g.articleSlug)
    }
}
