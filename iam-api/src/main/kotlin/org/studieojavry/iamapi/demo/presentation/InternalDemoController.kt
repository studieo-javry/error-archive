package org.studieojavry.iamapi.demo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.iamapi.demo.application.DemoResetService
import org.studieojavry.iamapi.demo.application.DemoWorkspaceProvisioner
import org.studieojavry.iamapi.demo.config.DemoProperties

/**
 * 데모 전용 내부 엔드포인트. `internal` 경로는 gateway 가 외부로 라우팅하지 않고 `X-Internal-Auth`(RS256)
 * 로만 접근 가능 — core-api 가 시드/리셋에 필요한 데모 ID 를 조회(identity)하거나, 운영자가 수동 리셋을 건다.
 */
@Tag(name = "internal-demo", description = "데모 identity 조회 / 게스트 수동 리셋 (내부 토큰 전용)")
@RestController
@RequestMapping("/internal/demo")
class InternalDemoController(
    private val provisioner: DemoWorkspaceProvisioner,
    private val resetService: DemoResetService,
    private val props: DemoProperties,
) {

    @Operation(summary = "[internal] 데모 워크스페이스/봇 ID 조회 — core-api 시드·리셋용")
    @GetMapping("/identity")
    fun identity(): DemoIdentityResponse {
        if (!props.enabled) return DemoIdentityResponse(enabled = false, workspaceId = null, botUserId = null)
        val d = provisioner.ensureProvisioned()
        return DemoIdentityResponse(
            enabled = true,
            workspaceId = d.workspaceId,
            botUserId = d.botUserId,
            personaUserIds = d.personaUserIds,
        )
    }

    @Operation(summary = "[internal] 게스트 사용자 수동 리셋 — 임시 계정+멤버십 삭제")
    @PostMapping("/reset-users")
    fun resetUsers(): Map<String, Any> = mapOf("removedGuests" to resetService.resetGuests())

    data class DemoIdentityResponse(
        val enabled: Boolean,
        val workspaceId: Long?,
        val botUserId: Long?,
        /** 시드 작성자 persona id 들(없거나 비활성이면 빈 목록). core-api 가 시드 소유자 분산에 사용. */
        val personaUserIds: List<Long> = emptyList(),
    )
}
