package org.studieojavry.iamapi.agentconnection.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 로컬 커넥터(@error-archive/agent) 설치 안내용 설정.
 *
 * 설치 명령 자체는 항상 `@latest` 를 사용하므로 최신 버전 조회 성공 여부에 의존하지 않는다.
 * 이 조회는 **표시·선택적 버전 고정(pin)** 용일 뿐이다. 패키지가 비공개(restricted)라 읽기 권한 있는
 * npm 토큰이 필요하다 — 공개로 전환하면 이 BE 조회를 제거하고 FE 가 npm Registry 를 직접 조회해도 된다.
 */
@ConfigurationProperties(prefix = "iam.agent-connector")
data class AgentConnectorProperties(
    val packageName: String = "@error-archive/agent",
    val npmRegistryUrl: String = "https://registry.npmjs.org",
    /** 비공개 패키지 읽기용 npm 토큰. 비면 조회 비활성(latest=null → FE 는 "확인 실패" 상태). */
    val npmToken: String? = null,
    /** 최신 버전 조회 결과 캐시 TTL(초). 0 이면 매 요청 재조회. */
    val versionCacheTtlSeconds: Long = 300,
)
