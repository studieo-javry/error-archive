package org.studieojavry.iamapi.auth.domain.model

import org.studieojavry.iamapi.auth.domain.model.exception.AccountNotActiveException
import org.studieojavry.iamapi.auth.domain.model.vo.Email
import org.studieojavry.iamapi.auth.domain.model.vo.Theme
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus
import java.time.Instant

class User private constructor(
    val id: Long?,
    var email: Email?,
    /**
     * 사용자 unique 식별자(== GitHub login 매핑).
     * MVP 정책: **불변** (변경 endpoint 없음). 검색/멘션 자동완성의 보조 매칭 + URL slug 후보.
     * mention token 은 userId 기반이므로 handle 변경 정책이 추후 도입돼도 멘션 깨지지 않음.
     */
    val handle: String,
    var displayName: String,
    var avatarUrl: String?,
    var bio: String?,
    var status: UserStatus,
    var pendingDeletionAt: Instant?,
    // ── Preferences (Phase 1) ──────────────────────────────────────
    /** IETF BCP 47 language tag(예: "ko", "en"). null=서버 기본/Accept-Language 사용. */
    var language: String?,
    /** IANA timezone(예: "Asia/Seoul"). null=서버 기본. */
    var timezone: String?,
    var theme: Theme,
    /** 사용자가 로그인 후 기본으로 열 워크스페이스. null=개인 홈. */
    var defaultWorkspaceId: Long?,
    // ───────────────────────────────────────────────────────────────
    val createdAt: Instant,
    var updatedAt: Instant
) {
    init {
        validateHandle(handle)
        validateDisplayName(displayName)
        validateAvatarUrl(avatarUrl)
        validateBio(bio)
        validateLanguage(language)
        validateTimezone(timezone)
    }

    fun ensureSignInAllowed() {
        if (!status.canSignIn()) {
            throw AccountNotActiveException(status)
        }
    }

    fun changeDisplayName(newDisplayName: String) {
        validateDisplayName(newDisplayName)
        if (this.displayName == newDisplayName) return
        this.displayName = newDisplayName
        touch()
    }

    fun changeAvatar(newAvatarUrl: String?) {
        validateAvatarUrl(newAvatarUrl)
        if (this.avatarUrl == newAvatarUrl) return
        this.avatarUrl = newAvatarUrl
        touch()
    }

    fun changeBio(newBio: String?) {
        val normalized = newBio?.trim()?.ifBlank { null }
        validateBio(normalized)
        if (this.bio == normalized) return
        this.bio = normalized
        touch()
    }

    // ── Preferences 변경 ───────────────────────────────────────────
    fun changeLanguage(newLanguage: String?) {
        val normalized = newLanguage?.trim()?.ifBlank { null }
        validateLanguage(normalized)
        if (this.language == normalized) return
        this.language = normalized
        touch()
    }

    fun changeTimezone(newTimezone: String?) {
        val normalized = newTimezone?.trim()?.ifBlank { null }
        validateTimezone(normalized)
        if (this.timezone == normalized) return
        this.timezone = normalized
        touch()
    }

    fun changeTheme(newTheme: Theme) {
        if (this.theme == newTheme) return
        this.theme = newTheme
        touch()
    }

    fun changeDefaultWorkspace(newWorkspaceId: Long?) {
        if (this.defaultWorkspaceId == newWorkspaceId) return
        this.defaultWorkspaceId = newWorkspaceId
        touch()
    }

    // ── 회원 탈퇴 라이프사이클 ─────────────────────────────────────
    // ACTIVE → PENDING_DELETION → (30일 후) DELETED
    //            ↑ restore       ↓ finalize (PII 익명화)

    /** 탈퇴 요청 — grace period 시작. ACTIVE 일 때만 호출 가능. */
    fun requestDeletion(at: Instant = Instant.now()) {
        check(status == UserStatus.ACTIVE) {
            "only ACTIVE users can request deletion: current=$status"
        }
        this.status = UserStatus.PENDING_DELETION
        this.pendingDeletionAt = at
        this.updatedAt = at
    }

    /** 탈퇴 취소(복구) — PENDING_DELETION 일 때만 호출 가능. */
    fun restore(at: Instant = Instant.now()) {
        check(status == UserStatus.PENDING_DELETION) {
            "only PENDING_DELETION users can be restored: current=$status"
        }
        this.status = UserStatus.ACTIVE
        this.pendingDeletionAt = null
        this.updatedAt = at
    }

    /**
     * 탈퇴 확정(grace 만료) — DELETED 로 전환하고 PII 익명화. 멱등(이미 DELETED 면 no-op).
     * email/displayName/avatarUrl/bio 를 비식별화: 이메일/아바타/바이오 null, displayName 은 표시용 placeholder.
     * preferences(language/timezone/theme/defaultWorkspaceId)도 초기화 — 더 이상 의미 없음.
     */
    fun finalizeDeletion(at: Instant = Instant.now()) {
        if (status == UserStatus.DELETED) return
        check(status == UserStatus.PENDING_DELETION) {
            "only PENDING_DELETION users can be finalized: current=$status"
        }
        this.email = null
        this.displayName = anonymizedDisplayName(id)
        this.avatarUrl = null
        this.bio = null
        this.language = null
        this.timezone = null
        this.theme = Theme.SYSTEM
        this.defaultWorkspaceId = null
        this.status = UserStatus.DELETED
        this.pendingDeletionAt = null
        this.updatedAt = at
    }

    private fun touch() {
        this.updatedAt = Instant.now()
    }

    companion object {
        const val HANDLE_MAX_LENGTH = 39
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val AVATAR_URL_MAX_LENGTH = 1024
        const val BIO_MAX_LENGTH = 280
        const val LANGUAGE_MAX_LENGTH = 16
        const val TIMEZONE_MAX_LENGTH = 64

        /**
         * handle 형식 — GitHub login 과 동일.
         * 영문 소문자 + 숫자 + 하이픈 (`a-z 0-9 -`), 시작은 영문/숫자, 끝은 하이픈 불가, 1-39자.
         */
        private val HANDLE_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?$")

        /** 탈퇴 확정 사용자의 표시명. UI 가 status=DELETED 를 보고 "탈퇴한 사용자" 로 렌더해도 됨. */
        fun anonymizedDisplayName(userId: Long?): String = "deleted_user_${userId ?: "unknown"}"

        fun create(email: Email?, handle: String, displayName: String, avatarUrl: String?): User {
            val now = Instant.now()
            return User(
                id = null,
                email = email,
                handle = handle,
                displayName = displayName,
                avatarUrl = avatarUrl,
                bio = null,
                status = UserStatus.ACTIVE,
                pendingDeletionAt = null,
                language = null,
                timezone = null,
                theme = Theme.SYSTEM,
                defaultWorkspaceId = null,
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            id: Long,
            email: Email?,
            handle: String,
            displayName: String,
            avatarUrl: String?,
            bio: String?,
            status: UserStatus,
            pendingDeletionAt: Instant?,
            language: String?,
            timezone: String?,
            theme: Theme,
            defaultWorkspaceId: Long?,
            createdAt: Instant,
            updatedAt: Instant
        ): User = User(
            id, email, handle, displayName, avatarUrl, bio, status, pendingDeletionAt,
            language, timezone, theme, defaultWorkspaceId,
            createdAt, updatedAt
        )

        private fun validateHandle(value: String) {
            require(value.isNotBlank()) { "handle must not be blank" }
            require(value.length <= HANDLE_MAX_LENGTH) {
                "handle must be $HANDLE_MAX_LENGTH chars or less: ${value.length}"
            }
            require(HANDLE_REGEX.matches(value)) {
                "handle must match ^[a-z0-9][a-z0-9-]*[a-z0-9]?$ (lowercase / digit / hyphen): $value"
            }
        }

        private fun validateDisplayName(value: String) {
            require(value.isNotBlank()) { "displayName must not be blank" }
            require(value.length <= DISPLAY_NAME_MAX_LENGTH) {
                "displayName must be $DISPLAY_NAME_MAX_LENGTH chars or less"
            }
        }

        private fun validateAvatarUrl(value: String?) {
            value ?: return
            require(value.length <= AVATAR_URL_MAX_LENGTH) {
                "avatarUrl must be $AVATAR_URL_MAX_LENGTH chars or less"
            }
            require(value.startsWith("https://") || value.startsWith("http://")) {
                "avatarUrl must start with http(s)://"
            }
        }

        private fun validateBio(value: String?) {
            value ?: return
            require(value.length <= BIO_MAX_LENGTH) {
                "bio must be $BIO_MAX_LENGTH chars or less"
            }
        }

        private fun validateLanguage(value: String?) {
            value ?: return
            require(value.length <= LANGUAGE_MAX_LENGTH) {
                "language must be $LANGUAGE_MAX_LENGTH chars or less"
            }
            // BCP 47 의 단순 유효성: 영문/숫자/하이픈만. 정확한 검증은 Locale.LanguageRange 등.
            require(value.matches(Regex("^[A-Za-z0-9-]+$"))) {
                "language must be a BCP 47 tag (e.g. 'ko', 'en-US'): $value"
            }
        }

        private fun validateTimezone(value: String?) {
            value ?: return
            require(value.length <= TIMEZONE_MAX_LENGTH) {
                "timezone must be $TIMEZONE_MAX_LENGTH chars or less"
            }
            // IANA tz 의 단순 유효성: java.time.ZoneId 가 받아들이면 OK.
            require(runCatching { java.time.ZoneId.of(value) }.isSuccess) {
                "timezone is not a valid IANA tz id: $value"
            }
        }
    }
}
