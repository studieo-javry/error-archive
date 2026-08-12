package org.studieojavry.iamapi.auth.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.auth.domain.model.vo.Email
import org.studieojavry.iamapi.auth.domain.model.vo.Theme
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import java.time.Instant

@Entity
@Table(
    name = "iam_user",
    indexes = [
        Index(name = "ix_iam_user_email", columnList = "email"),
        Index(name = "ux_iam_user_handle", columnList = "handle", unique = true),
    ]
)
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "email", length = 254)
    var email: String?,

    /**
     * 사용자 unique handle (GitHub login 매핑). MVP 정책: 변경 불가.
     * 저장은 소문자 정규화, 검색은 case-insensitive — `LOWER(handle)` functional unique index 가 더 정확하지만
     * JPA 표준은 simple unique 만 지원해 인덱스로 보강.
     */
    @Column(name = "handle", nullable = false, length = 39, unique = true, updatable = false)
    var handle: String,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Column(name = "avatar_url", length = 1024)
    var avatarUrl: String?,

    @Column(name = "bio", length = 280)
    var bio: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    var status: UserStatus,

    /** PENDING_DELETION 으로 전환된 시각. grace period 만료 판정에 사용. ACTIVE 일 땐 null. */
    @Column(name = "pending_deletion_at")
    var pendingDeletionAt: Instant?,

    // ── Preferences (Phase 1) ──────────────────────────────────────
    /** BCP 47 language tag(예: "ko","en-US"). null=서버 기본. */
    @Column(name = "language", length = 16)
    var language: String?,

    /** IANA timezone(예: "Asia/Seoul"). null=서버 기본. */
    @Column(name = "timezone", length = 64)
    var timezone: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 16)
    var theme: Theme,

    /** 로그인 후 기본 진입 워크스페이스. null=개인 홈. */
    @Column(name = "default_workspace_id")
    var defaultWorkspaceId: Long?,
    // ───────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {

    fun toDomain(): User = User.Companion.rehydrate(
        id = id!!,
        email = email?.let { Email(it) },
        handle = handle,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        status = status,
        pendingDeletionAt = pendingDeletionAt,
        language = language,
        timezone = timezone,
        theme = theme,
        defaultWorkspaceId = defaultWorkspaceId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id,
            email = user.email?.value,
            handle = user.handle,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            status = user.status,
            pendingDeletionAt = user.pendingDeletionAt,
            language = user.language,
            timezone = user.timezone,
            theme = user.theme,
            defaultWorkspaceId = user.defaultWorkspaceId,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }
}
