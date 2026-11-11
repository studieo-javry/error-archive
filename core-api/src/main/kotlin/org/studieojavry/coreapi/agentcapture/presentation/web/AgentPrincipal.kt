package org.studieojavry.coreapi.agentcapture.presentation.web

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * agent 전용 경로의 호출 주체. 게이트웨이가 iam introspection 으로 검증한 뒤 내부 JWT 의
 * sub/cid/scp 클레임으로 실어 보낸 값을, 컨트롤러 인자(AgentPrincipalArgumentResolver)로 받는다.
 *
 * 일반 웹 사용자 토큰과 달리 [connectionId] 가 항상 존재한다(없으면 agent 호출이 아니다).
 */
data class AgentPrincipal(
    val userId: Long,
    val connectionId: String,
    val scopes: Set<String>,
) {
    fun hasScope(scope: String): Boolean = scope in scopes

    /** 범위 밖 호출은 403. 경로 제한에 더해 application 권한 검사를 이중으로 적용한다. */
    fun requireScope(scope: String) {
        if (!hasScope(scope)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "agent token lacks required scope: $scope")
        }
    }
}

/** agent 토큰 scope 상수(iam AgentScope.wire 와 동일 문자열 — 스키마 중복, 변경 시 동반 수정). */
object AgentScopes {
    const val CASE_READ = "case:read"
    const val CAPTURE_READ = "capture:read"
    const val CAPTURE_WRITE = "capture:write"
    const val ARTIFACT_WRITE = "artifact:write"
}
