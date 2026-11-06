package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization

/**
 * GitHub 사용자 인가(암호화 토큰) 저장소. 어댑터가 저장/조회 경계에서 토큰을 암복호화한다(도메인은 평문).
 */
interface GithubUserAuthorizationPort {

    /** EA 사용자당 1건 upsert(재인가 시 최신 토큰·GitHub 계정으로 교체). */
    fun save(authorization: GithubUserAuthorization): GithubUserAuthorization

    /** 이 EA 사용자의 저장된 인가(없으면 null). 토큰은 복호화해 돌려준다. 복호화 불가(키 로테이션 등)면 null 로 간주. */
    fun findByEaUserId(eaUserId: Long): GithubUserAuthorization?

    /** 이 EA 사용자의 저장 인가 1건 폐기. 토큰이 만료·무효로 확인됐을 때의 반응 폐기(이 사용자만). 삭제한 건수 반환. */
    fun deleteByEaUserId(eaUserId: Long): Int

    /** 이 GitHub 사용자 id 로 저장된 인가를 모두 폐기(인가 취소 웹훅 — 같은 GitHub 계정의 모든 EA 링크). 삭제한 건수 반환. */
    fun deleteByGithubUserId(githubUserId: Long): Int
}
