package org.studieojavry.iamapi.auth.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import org.studieojavry.iamapi.auth.infrastructure.jpa.entity.UserEntity
import java.time.Instant

interface UserJpaRepository : JpaRepository<UserEntity, Long> {
    fun findFirstByEmailIgnoreCase(email: String): UserEntity?

    /** OAuth 매핑 시 handle 충돌 검사. */
    fun existsByHandleIgnoreCase(handle: String): Boolean

    /** 데모 봇 부트스트랩 멱등성 — handle 로 단건 조회. */
    fun findFirstByHandleIgnoreCase(handle: String): UserEntity?

    /** 게스트("로그인 없이 둘러보기") 사용자 — 야간 리셋 정리 대상(가장 오래된 것부터). */
    fun findByIsGuestTrueOrderByIdAsc(pageable: Pageable): List<UserEntity>

    /**
     * 게스트 사용자 일괄 삭제 — 야간 리셋. 벌크 DELETE(단일 SQL)로 1차 캐시 우회.
     * `clearAutomatically` 로 영속성 컨텍스트를 비워 이후 조회가 DB 를 반영하게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserEntity u where u.isGuest = true and u.handle <> :keepHandle")
    fun deleteGuestsExcept(@Param("keepHandle") keepHandle: String): Int

    /** handle prefix 검색 (대소문자 무시), ACTIVE 만. — 멘션 자동완성에서 handle 매칭용. */
    fun findByStatusAndHandleStartingWithIgnoreCaseOrderByHandleAsc(
        status: UserStatus,
        prefix: String,
        pageable: Pageable,
    ): List<UserEntity>

    /**
     * 회원 탈퇴 finalize 배치용. status + pendingDeletionAt < threshold 인 사용자를 오래된 순으로.
     */
    fun findByStatusAndPendingDeletionAtBeforeOrderByPendingDeletionAtAsc(
        status: UserStatus,
        threshold: Instant,
        pageable: Pageable,
    ): List<UserEntity>

    /** displayName prefix 검색 (대소문자 무시), ACTIVE 만. 가나다/abc 순. */
    fun findByStatusAndDisplayNameStartingWithIgnoreCaseOrderByDisplayNameAsc(
        status: UserStatus,
        prefix: String,
        pageable: Pageable,
    ): List<UserEntity>

    /** 빈 prefix 일 때 — 최근 가입한 ACTIVE 사용자. */
    fun findByStatusOrderByCreatedAtDesc(
        status: UserStatus,
        pageable: Pageable,
    ): List<UserEntity>

    /** 멘션 식별자 → userId 매핑용 (대소문자 무시 정확 일치). */
    fun findByStatusAndDisplayNameIgnoreCase(
        status: UserStatus,
        displayName: String,
    ): List<UserEntity>

    fun findAllByIdIn(ids: Collection<Long>): List<UserEntity>
}
