package org.studieojavry.iamapi.agentconnection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.agentconnection.application.usecase.AgentConnectionNotFoundException
import org.studieojavry.iamapi.agentconnection.application.usecase.CreateAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.IntrospectAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.InvalidAgentTokenException
import org.studieojavry.iamapi.agentconnection.application.usecase.ListAgentConnectionsUseCase
import org.studieojavry.iamapi.agentconnection.application.usecase.RevokeAgentConnectionUseCase
import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import java.util.UUID

/**
 * agent 연결 토큰 발급·검증·폐기 전체 흐름(H2, 실제 Spring context).
 * iam_agent_connection 은 iam_user 로의 FK 가 없어 임의 userId 로 검증 가능.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentConnectionFlowTest(
    @Autowired private val create: CreateAgentConnectionUseCase,
    @Autowired private val list: ListAgentConnectionsUseCase,
    @Autowired private val revoke: RevokeAgentConnectionUseCase,
    @Autowired private val introspect: IntrospectAgentConnectionUseCase,
) {
    private val userId = 4242L

    @Test
    fun `발급 토큰은 introspection 으로 같은 principal 을 돌려준다`() {
        val result = create.invoke(userId, label = "MacBook", scopes = AgentScope.DEFAULTS)

        assertThat(result.rawToken).startsWith("eaa_")
        val principal = introspect.invoke(result.rawToken)
        assertThat(principal.userId).isEqualTo(userId)
        assertThat(principal.connectionId).isEqualTo(result.connection.connectionId)
        assertThat(principal.scopes).isEqualTo(AgentScope.DEFAULTS)

        val items = list.invoke(userId)
        assertThat(items).hasSize(1)
        assertThat(items.first().label).isEqualTo("MacBook")
        assertThat(items.first().expired).isFalse()
    }

    @Test
    fun `빈 scopes 는 기본 전체로 발급된다`() {
        val result = create.invoke(userId, label = null, scopes = emptySet())
        assertThat(result.connection.scopes).isEqualTo(AgentScope.DEFAULTS)
    }

    @Test
    fun `알 수 없는 토큰은 invalid 다`() {
        assertThatThrownBy { introspect.invoke("eaa_not-a-real-token") }
            .isInstanceOf(InvalidAgentTokenException::class.java)
        assertThatThrownBy { introspect.invoke("") }
            .isInstanceOf(InvalidAgentTokenException::class.java)
    }

    @Test
    fun `폐기 후에는 즉시 invalid 이고 목록에서 사라진다`() {
        val result = create.invoke(userId, label = null, scopes = AgentScope.DEFAULTS)

        revoke.invoke(userId, result.connection.connectionId)

        assertThatThrownBy { introspect.invoke(result.rawToken) }
            .isInstanceOf(InvalidAgentTokenException::class.java)
        assertThat(list.invoke(userId)).isEmpty()
    }

    @Test
    fun `커넥터 설치 메타는 first-use 에 1회 바인딩되고 목록에 표시된다`() {
        val result = create.invoke(userId, label = null, scopes = AgentScope.DEFAULTS)
        val desc = IntrospectAgentConnectionUseCase.ClientDescriptor(
            kind = "claude-code", version = "1.0.0", installId = "inst-1", installLabel = "내 맥북",
        )

        introspect.invoke(result.rawToken, desc)

        val item = list.invoke(userId).single()
        assertThat(item.clientKind).isEqualTo("claude-code")
        assertThat(item.clientVersion).isEqualTo("1.0.0")
        assertThat(item.installLabel).isEqualTo("내 맥북")
        // label 이 없으므로 표시 이름은 설치 라벨로 떨어진다
        assertThat(item.displayName).isEqualTo("내 맥북")
    }

    @Test
    fun `이미 바인딩된 뒤에는 버전만 갱신되고 설치 식별자는 고정된다`() {
        val result = create.invoke(userId, label = "데스크탑", scopes = AgentScope.DEFAULTS)
        introspect.invoke(result.rawToken, IntrospectAgentConnectionUseCase.ClientDescriptor("claude-code", "1.0.0", "inst-A", "A"))
        // 재사용: 버전 업, installId/label 은 다른 값이 와도 바뀌지 않음
        introspect.invoke(result.rawToken, IntrospectAgentConnectionUseCase.ClientDescriptor("claude-code", "1.2.0", "inst-B", "B"))

        val item = list.invoke(userId).single()
        assertThat(item.clientVersion).isEqualTo("1.2.0") // 버전만 갱신
        assertThat(item.installLabel).isEqualTo("A")      // 식별자·라벨 고정
        assertThat(item.displayName).isEqualTo("데스크탑") // label 우선
    }

    @Test
    fun `설치 메타 없는(구형) 커넥터는 그대로 동작하고 표시 이름은 폴백한다`() {
        val result = create.invoke(userId, label = null, scopes = AgentScope.DEFAULTS)
        introspect.invoke(result.rawToken) // descriptor 없음

        val item = list.invoke(userId).single()
        assertThat(item.clientKind).isNull()
        assertThat(item.installLabel).isNull()
        // label·설치라벨 모두 없음 → "{cid 앞 8자}" 로 폴백(공백 구분자 없음)
        assertThat(item.displayName).isEqualTo(result.connection.connectionId.toString().take(8))
    }

    @Test
    fun `타인 또는 모르는 연결 폐기는 404 신호다`() {
        val result = create.invoke(userId, label = null, scopes = AgentScope.DEFAULTS)

        // 다른 소유자
        assertThatThrownBy { revoke.invoke(9999L, result.connection.connectionId) }
            .isInstanceOf(AgentConnectionNotFoundException::class.java)
        // 모르는 connectionId
        assertThatThrownBy { revoke.invoke(userId, UUID.randomUUID()) }
            .isInstanceOf(AgentConnectionNotFoundException::class.java)
    }
}
