package org.studieojavry.coreapi.demo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.coreapi.demo.application.DemoResetService

/**
 * 데모 콘텐츠 수동 리셋(내부 토큰 전용). `internal` 경로라 gateway 외부 노출 없음 — 운영자/점검용.
 * 평상시 리셋은 `DemoResetService` 의 야간 스케줄이 수행한다.
 */
@Tag(name = "internal-demo", description = "데모 워크스페이스 콘텐츠 수동 wipe+reseed (내부 토큰 전용)")
@RestController
@RequestMapping("/internal/demo")
class InternalDemoResetController(
    private val resetService: DemoResetService,
) {

    @Operation(summary = "[internal] 데모 콘텐츠 리셋 — 데모 WS 전체 케이스 wipe 후 봇 시드 재생성")
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        val r = resetService.reset()
        return mapOf("ran" to r.ran, "wiped" to r.wiped, "seeded" to r.seeded)
    }
}
