package org.studieojavry.coreapi.agentcapture

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.coreapi.agentcapture.presentation.web.AgentPrincipal
import org.studieojavry.coreapi.agentcapture.presentation.web.AgentPrincipalArgumentResolver
import org.studieojavry.coreapi.agentcapture.presentation.web.AgentScopes
import org.studieojavry.coreapi.agentcapture.presentation.web.AgentSessionController
import org.studieojavry.internalauth.InternalAuthentication

/**
 * agent principal 조립: 내부 JWT(cid/scp)로 세팅된 InternalAuthentication → AgentPrincipal.
 * 사용자 토큰(connectionId 없음)은 agent 경로 호출 자격이 아니므로 거부.
 */
class AgentPrincipalArgumentResolverTest {

    private val resolver = AgentPrincipalArgumentResolver()
    private val param = MethodParameter(
        AgentSessionController::class.java.getMethod("me", AgentPrincipal::class.java), 0,
    )
    private val webRequest = ServletWebRequest(MockHttpServletRequest())

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(auth: InternalAuthentication) {
        SecurityContextHolder.getContext().authentication = auth
    }

    @Test
    fun `agent 토큰은 userId connectionId scopes 로 조립된다`() {
        authenticate(
            InternalAuthentication(
                userId = 42L,
                authorities = listOf(SimpleGrantedAuthority("SCOPE_capture:write")),
                callerService = "gateway",
                connectionId = "conn-1",
                scopes = listOf("capture:write", "case:read"),
            )
        )
        val principal = resolver.resolveArgument(param, null, webRequest, null)
        assertThat(principal.userId).isEqualTo(42L)
        assertThat(principal.connectionId).isEqualTo("conn-1")
        assertThat(principal.scopes).containsExactlyInAnyOrder("capture:write", "case:read")
    }

    @Test
    fun `connectionId 없는 사용자 토큰은 401`() {
        authenticate(InternalAuthentication(7L, emptyList(), "gateway"))
        assertThatThrownBy { resolver.resolveArgument(param, null, webRequest, null) }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `인증 없음은 401`() {
        assertThatThrownBy { resolver.resolveArgument(param, null, webRequest, null) }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `requireScope 는 범위 밖이면 403`() {
        val principal = AgentPrincipal(1L, "c", setOf(AgentScopes.CASE_READ))
        principal.requireScope(AgentScopes.CASE_READ) // 통과
        assertThatThrownBy { principal.requireScope(AgentScopes.CAPTURE_WRITE) }
            .isInstanceOf(ResponseStatusException::class.java)
    }
}
