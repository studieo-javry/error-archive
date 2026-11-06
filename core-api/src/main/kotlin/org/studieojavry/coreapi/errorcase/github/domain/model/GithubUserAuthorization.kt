package org.studieojavry.coreapi.errorcase.github.domain.model

import java.time.Instant

/**
 * 한 EA 사용자가 GitHub OAuth 로 내준 사용자 토큰(user-to-server). 저장 목적:
 *  - 시나리오 ②(재연결 시 GitHub 재이동 없이 설치/레포를 다시 조회) 를 위해 유효한 사용자 토큰을 재사용.
 *  - `github_app_authorization` revoked 웹훅이 오면 [githubUserId] 로 찾아 폐기.
 *
 * [token] 은 평문(도메인 경계 안). 저장 어댑터가 AES-GCM 으로 암호화해 넣고 꺼낼 때 복호화한다 — DB 에는 평문이 없다.
 * EA 사용자당 1건(재인가 시 upsert). 사용자가 GitHub 계정을 바꾸면 [githubUserId] 가 바뀌며 덮어쓴다.
 */
data class GithubUserAuthorization(
    val eaUserId: Long,
    val githubUserId: Long,
    val githubLogin: String,
    val token: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
