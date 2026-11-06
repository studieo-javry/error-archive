package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubUserAuthorizationEntity

interface GithubUserAuthorizationJpaRepository : JpaRepository<GithubUserAuthorizationEntity, Long> {

    /** revoked 웹훅 → 이 GitHub 사용자 id 로 저장된 인가 모두 삭제. 삭제 건수 반환. */
    @Modifying
    @Query("DELETE FROM GithubUserAuthorizationEntity a WHERE a.githubUserId = :githubUserId")
    fun deleteByGithubUserId(githubUserId: Long): Int
}
