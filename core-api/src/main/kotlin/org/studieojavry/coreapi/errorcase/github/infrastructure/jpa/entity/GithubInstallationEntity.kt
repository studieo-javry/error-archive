package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubInstallation
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubInstallationStatus
import java.time.LocalDateTime

@Entity
@Table(
    name = "github_installation",
    indexes = [
        Index(name = "ix_github_installation_user", columnList = "installed_by_user_id"),
        Index(name = "ix_github_installation_workspace", columnList = "workspace_id"),
    ],
)
class GithubInstallationEntity(
    @Id
    @Column(name = "installation_id")
    var installationId: Long,

    @Column(name = "account_login", nullable = false, length = 100)
    var accountLogin: String,

    @Column(name = "account_type", nullable = false, length = 20)
    var accountType: String,

    @Column(name = "workspace_id")
    var workspaceId: Long?,

    @Column(name = "installed_by_user_id", nullable = false)
    var installedByUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: GithubInstallationStatus,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    fun toDomain() = GithubInstallation.reconstitute(
        installationId, accountLogin, accountType, workspaceId, installedByUserId, status, createdAt, updatedAt,
    )

    companion object {
        fun from(d: GithubInstallation) = GithubInstallationEntity(
            d.installationId, d.accountLogin, d.accountType, d.workspaceId, d.installedByUserId,
            d.status, d.createdAt, d.updatedAt,
        )
    }
}
