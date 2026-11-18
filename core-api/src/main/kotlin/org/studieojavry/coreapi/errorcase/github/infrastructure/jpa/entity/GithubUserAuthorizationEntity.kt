package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * `github_user_authorization` — EA 사용자당 1건(@Id = eaUserId). access/refresh 토큰 모두 AES-GCM
 * ciphertext + IV 분리 저장(평문 없음). [githubUserId] 인덱스로 revoked 웹훅의 sender.id → 폐기 조회.
 * refresh 토큰·만료 컬럼은 nullable — App 이 토큰 만료를 끄면 갱신 정보가 없고, V18 레거시 행도 비어 있다.
 */
@Entity
@Table(
    name = "github_user_authorization",
    indexes = [Index(name = "ix_github_user_authorization_github_user", columnList = "github_user_id")],
)
class GithubUserAuthorizationEntity(
    @Id
    @Column(name = "ea_user_id")
    var eaUserId: Long = 0,

    @Column(name = "github_user_id", nullable = false)
    var githubUserId: Long = 0,

    @Column(name = "github_login", nullable = false, length = 255)
    var githubLogin: String = "",

    @Column(name = "token_ciphertext", nullable = false)
    var tokenCiphertext: ByteArray = ByteArray(0),

    @Column(name = "token_iv", nullable = false)
    var tokenIv: ByteArray = ByteArray(0),

    @Column(name = "refresh_token_ciphertext")
    var refreshTokenCiphertext: ByteArray? = null,

    @Column(name = "refresh_token_iv")
    var refreshTokenIv: ByteArray? = null,

    @Column(name = "access_token_expires_at")
    var accessTokenExpiresAt: Instant? = null,

    @Column(name = "refresh_token_expires_at")
    var refreshTokenExpiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
