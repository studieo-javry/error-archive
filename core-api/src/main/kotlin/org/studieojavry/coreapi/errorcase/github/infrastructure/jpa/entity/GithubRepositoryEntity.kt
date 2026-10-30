package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import java.time.Instant

@Entity
@Table(
    name = "github_repository",
    indexes = [Index(name = "ix_github_repository_installation", columnList = "installation_id")],
)
class GithubRepositoryEntity(
    @Id
    @Column(name = "repo_id")
    var repoId: Long,

    @Column(name = "installation_id", nullable = false)
    var installationId: Long,

    @Column(name = "full_name", nullable = false, length = 200)
    var fullName: String,

    @Column(name = "is_private", nullable = false)
    var isPrivate: Boolean,

    @Column(name = "removed_at")
    var removedAt: Instant?,

    @Column(name = "source_updated_at")
    var sourceUpdatedAt: Instant?,
) {
    fun toDomain() = GithubRepository.reconstitute(repoId, installationId, fullName, isPrivate, removedAt, sourceUpdatedAt)

    companion object {
        fun from(d: GithubRepository) = GithubRepositoryEntity(
            d.repoId, d.installationId, d.fullName, d.isPrivate, d.removedAt, d.sourceUpdatedAt,
        )
    }
}
