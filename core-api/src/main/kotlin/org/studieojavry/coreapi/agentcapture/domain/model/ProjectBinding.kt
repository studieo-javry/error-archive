package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant

/**
 * 프로젝트 바인딩(이슈 #144 §2 / #145 "승인된 프로젝트") — 로컬 경로↔프로젝트↔repo 를 묶는 정규 단위.
 *
 * P0 의 free-text `project` 문자열을 대체·정규화한다. `case:read` 스코프와 Hook 자동수집(P1)은 이 바인딩을
 * 경계로 삼는다(프로젝트/ repo 이름이 같다는 이유만으로 권한을 넓히지 않는다 — 소유자·명시 매핑 기준).
 */
data class ProjectBinding(
    val id: Long? = null,
    val ownerUserId: Long,
    val projectKey: String,
    /** 정규화한 repo 식별자(GitHub 연결 시). 없으면 null. */
    val normalizedRepoId: String? = null,
    val storagePolicy: StoragePolicy = StoragePolicy.DEFAULT,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 바인딩의 근거·첨부 저장 공간 정책. P1 에서 확장(워크스페이스 공유 등). */
enum class StoragePolicy { DEFAULT }
