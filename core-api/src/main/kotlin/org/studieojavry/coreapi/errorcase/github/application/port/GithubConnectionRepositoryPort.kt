package org.studieojavry.coreapi.errorcase.github.application.port

import org.studieojavry.coreapi.errorcase.github.domain.model.GithubConnection
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubRepository
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import java.time.Instant

/**
 * 설치 ↔ 공간 연결 + 연결별 레포 선택을 다루는 포트.
 *
 * 핵심 규칙 메서드는 [isRepoSelectedInSpace] — "이 공간에 ACTIVE 연결이 있고 그 연결이 이 repo 를 선택했나".
 * repo·설치의 ACTIVE 여부는 호출부(resolve) 또는 [findSelectedActiveRepositories] 조인이 함께 본다.
 */
interface GithubConnectionRepositoryPort {
    fun save(connection: GithubConnection): GithubConnection
    fun findByConnectionId(connectionId: Long): GithubConnection?

    /** 설치 × 공간의 ACTIVE 연결(최대 1). */
    fun findActive(installationId: Long, space: GithubSpace): GithubConnection?

    /** 설치 × 공간의 연결(DISCONNECTED 포함) — 재연결 시 되살리기용. */
    fun findAnyForSpaceAndInstallation(installationId: Long, space: GithubSpace): GithubConnection?

    /** 이 공간들의 ACTIVE 연결 전부(관리 화면/목록용). */
    fun findActiveBySpaces(spaces: Collection<GithubSpace>): List<GithubConnection>

    /** 설치의 ACTIVE 연결 전부 — 웹훅 팬아웃에서 "어느 공간들이 쓰나" 파악용. */
    fun findActiveByInstallation(installationId: Long): List<GithubConnection>

    // ── 핵심 규칙 ──
    /** 이 공간의 ACTIVE 연결이 이 repo 를 선택했나. */
    fun isRepoSelectedInSpace(space: GithubSpace, repoId: Long): Boolean

    /** 주어진 공간들 중, 이 repo 를 선택한 ACTIVE 연결을 가진 공간 집합(웹훅 fan-out 필터). */
    fun spacesSelectingRepo(spaces: Collection<GithubSpace>, repoId: Long): Set<GithubSpace>

    /** 공간에 선택된 활성 레포(설치 ACTIVE + repo 활성). GithubReferenceScope 대체. */
    fun findSelectedActiveRepositories(space: GithubSpace): List<GithubRepository>

    // ── 선택 편집 ──
    fun selectedRepoIds(connectionId: Long): Set<Long>

    /** 연결의 선택 레포를 주어진 집합으로 교체(replace-set). */
    fun replaceSelectedRepos(connectionId: Long, repoIds: Collection<Long>, now: Instant)
}
