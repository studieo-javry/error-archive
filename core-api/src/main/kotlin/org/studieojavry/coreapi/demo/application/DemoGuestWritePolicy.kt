package org.studieojavry.coreapi.demo.application

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import java.time.Duration
import java.time.Instant

/**
 * 게스트("로그인 없이 둘러보기") 쓰기 범위 = **데모 워크스페이스 안**.
 *
 * 게스트는 데모 WS 의 WRITE 멤버라 일반 권한 검사만으로는 개인 PUBLIC 케이스 생성, 공개 케이스(쇼케이스 포함)에
 * 댓글·me-too 등을 남길 수 있다. 그 흔적은 야간 리셋으로도 지워지지 않고 회원 Search 에 남으므로 서버에서 막는다.
 * 데모 WS id 를 모르면(iam 장애·데모 비활성) 거부한다(fail-closed).
 */
@Component
class DemoGuestWritePolicy(
    private val identityClient: DemoIamIdentityClient,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {
    @Volatile private var cached: Pair<Long, Instant>? = null

    fun isGuest(authentication: Authentication?): Boolean =
        authentication?.authorities?.any { it.authority == ROLE_GUEST } == true

    /** 게스트가 이 워크스페이스에 케이스를 만들 수 있는가 — 데모 WS 만. 개인(null) 은 불가. */
    fun canCreateIn(workspaceId: Long?): Boolean =
        workspaceId != null && workspaceId == demoWorkspaceId()

    /** 게스트가 이 케이스(와 그 하위 리소스)를 변경할 수 있는가 — 데모 WS 소속 케이스만. */
    fun canWriteCase(errorCaseId: Long): Boolean {
        val demoWs = demoWorkspaceId() ?: return false
        return errorCaseRepository.findWorkspaceIdById(errorCaseId) == demoWs
    }

    /** 데모 WS id — 성공한 조회만 [CACHE_TTL] 동안 캐시(데모 WS 는 사실상 고정). */
    private fun demoWorkspaceId(): Long? {
        val now = Instant.now()
        cached?.let { (id, at) -> if (Duration.between(at, now) < CACHE_TTL) return id }
        val identity = identityClient.fetchIdentity()?.takeIf { it.enabled } ?: return null
        val id = identity.workspaceId ?: return null
        cached = id to now
        return id
    }

    companion object {
        const val ROLE_GUEST = "ROLE_GUEST"
        private val CACHE_TTL: Duration = Duration.ofMinutes(5)
    }
}
