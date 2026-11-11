package org.studieojavry.internalauth

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

/**
 * internal token 검증 성공 시 SecurityContext 에 담기는 Authentication.
 *
 *  - principal  = 원 사용자 ID (Long) → 컨트롤러에서 @AuthenticationPrincipal 로 주입
 *  - name       = 사용자 ID 문자열 → authentication.name 을 쓰던 기존 코드 호환
 *  - callerService = iss 클레임 (어느 서비스가 호출했는가). 메쉬 도입 후엔 mTLS 가 대체.
 *  - connectionId = agent 연결 토큰 경로에서만 채워지는 `cid` 클레임(그 외 null).
 *  - scopes       = agent 세분 권한(`scp` 클레임). 사용자 JWT 경로에선 빈 리스트.
 *
 * agent principal 과 사용자 principal 모두 principal 타입은 Long(userId) 로 동일하다 — 기존
 * `@AuthenticationPrincipal userId: Long` 컨트롤러가 그대로 동작한다. agent 전용 컨텍스트
 * (connectionId/scopes)가 필요한 핸들러는 SecurityContext 에서 이 타입으로 캐스팅해 읽는다.
 */
class InternalAuthentication(
    private val userId: Long,
    authorities: Collection<GrantedAuthority>,
    val callerService: String,
    val connectionId: String? = null,
    val scopes: List<String> = emptyList(),
) : AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Long = userId
    override fun getCredentials(): Any? = null
    override fun getName(): String = userId.toString()
}
