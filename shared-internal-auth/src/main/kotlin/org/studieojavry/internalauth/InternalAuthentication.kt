package org.studieojavry.internalauth

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

/**
 * internal token 검증 성공 시 SecurityContext 에 담기는 Authentication.
 *
 *  - principal  = 원 사용자 ID (Long) → 컨트롤러에서 @AuthenticationPrincipal 로 주입
 *  - name       = 사용자 ID 문자열 → authentication.name 을 쓰던 기존 코드 호환
 *  - callerService = iss 클레임 (어느 서비스가 호출했는가). 메쉬 도입 후엔 mTLS 가 대체.
 */
class InternalAuthentication(
    private val userId: Long,
    authorities: Collection<GrantedAuthority>,
    val callerService: String,
) : AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Long = userId
    override fun getCredentials(): Any? = null
    override fun getName(): String = userId.toString()
}
