package org.studieojavry.iamapi.auth.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import org.studieojavry.iamapi.auth.domain.model.vo.Theme

/**
 * Patch-style 업데이트.
 * - null/미포함=유지. `bio/language/timezone/defaultWorkspaceId` 는 비우려면 `clear*:true`.
 * - **`avatarUrl` 은 *set 만 지원*** — 비우려면 `DELETE /users/me/avatar` 사용
 *   (파일 자원 라이프사이클이 별도라 storage 정리까지 한 endpoint 에 묶음).
 * - theme 은 enum 이라 항상 한 값. SYSTEM 으로 설정하면 OS 따라감.
 */
@Schema(description = "내 프로필·환경설정 부분 수정")
data class UpdateMyProfileRequest(
    @field:Size(min = 1, max = 100)
    val displayName: String? = null,

    @field:Schema(description = "외부 호스팅 이미지 URL 을 직접 박는 케이스. 업로드는 `POST /users/me/avatar` (multipart) 사용. 비우기는 `DELETE /users/me/avatar`.")
    @field:Size(max = 1024)
    val avatarUrl: String? = null,

    @field:Size(max = 280)
    val bio: String? = null,

    val clearBio: Boolean = false,

    // ── Preferences (Phase 1) ──
    @field:Schema(description = "BCP 47 language tag", example = "ko")
    @field:Size(max = 16)
    val language: String? = null,
    val clearLanguage: Boolean = false,

    @field:Schema(description = "IANA timezone id", example = "Asia/Seoul")
    @field:Size(max = 64)
    val timezone: String? = null,
    val clearTimezone: Boolean = false,

    @field:Schema(description = "UI 테마. SYSTEM 은 OS 설정 따름.", example = "DARK")
    val theme: Theme? = null,

    @field:Schema(description = "로그인 후 기본 진입 워크스페이스 ID. null=개인 홈.")
    val defaultWorkspaceId: Long? = null,
    val clearDefaultWorkspace: Boolean = false,
)
