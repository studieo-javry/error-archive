package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.adapter

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.github.application.port.GithubUserAuthorizationPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubUserAuthorization
import org.studieojavry.coreapi.errorcase.github.infrastructure.crypto.GithubTokenCipher
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubUserAuthorizationJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubUserAuthorizationEntity

/**
 * 토큰 암복호화를 저장 경계에서 수행한다 — 도메인/유스케이스는 평문만 본다. @ConditionalOnGithubEnabled:
 * [GithubTokenCipher] 가 암호화 키를 요구하므로 연동 비활성이면 함께 미등록.
 */
@Component
@ConditionalOnGithubEnabled
class GithubUserAuthorizationRepositoryAdapter(
    private val repository: GithubUserAuthorizationJpaRepository,
    private val cipher: GithubTokenCipher,
) : GithubUserAuthorizationPort {

    override fun save(authorization: GithubUserAuthorization): GithubUserAuthorization {
        val enc = cipher.encrypt(authorization.token)
        val entity = repository.findById(authorization.eaUserId).orElse(null)?.apply {
            githubUserId = authorization.githubUserId
            githubLogin = authorization.githubLogin
            tokenCiphertext = enc.ciphertext
            tokenIv = enc.iv
            updatedAt = authorization.updatedAt
        } ?: GithubUserAuthorizationEntity(
            eaUserId = authorization.eaUserId,
            githubUserId = authorization.githubUserId,
            githubLogin = authorization.githubLogin,
            tokenCiphertext = enc.ciphertext,
            tokenIv = enc.iv,
            createdAt = authorization.createdAt,
            updatedAt = authorization.updatedAt,
        )
        repository.save(entity)
        return authorization
    }

    override fun findByEaUserId(eaUserId: Long): GithubUserAuthorization? =
        repository.findById(eaUserId).orElse(null)?.let { e ->
            GithubUserAuthorization(
                eaUserId = e.eaUserId,
                githubUserId = e.githubUserId,
                githubLogin = e.githubLogin,
                token = cipher.decrypt(GithubTokenCipher.Encrypted(e.tokenIv, e.tokenCiphertext)),
                createdAt = e.createdAt,
                updatedAt = e.updatedAt,
            )
        }

    // @Modifying DELETE 는 트랜잭션을 요구한다. 웹훅 경로엔 바깥 tx 가 있지만 connection-options(무효 토큰 폐기)엔 없으므로
    // 어댑터가 자체 tx 를 연다(바깥 tx 가 있으면 합류 — REQUIRED).
    @Transactional
    override fun deleteByGithubUserId(githubUserId: Long): Int = repository.deleteByGithubUserId(githubUserId)
}
