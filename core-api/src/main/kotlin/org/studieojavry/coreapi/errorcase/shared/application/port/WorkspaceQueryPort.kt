package org.studieojavry.coreapi.errorcase.shared.application.port

interface WorkspaceQueryPort {

    fun getAvailableWorkspaces(userId: Long): List<WorkspaceSummary>

    /**
     * 호출 사용자의 워크스페이스 역할을 조회한다. **멤버가 아니면 null**.
     * 콘텐츠(에러케이스) 권한 판정에 사용 — 멤버 여부뿐 아니라 READ/WRITE/ADMIN 구분.
     */
    fun getViewerRole(userId: Long, workspaceId: Long): WorkspaceRole?

    data class WorkspaceSummary(
        val workpaceId: Long,
        val name: String
    )

    /**
     * iam-api 워크스페이스 역할의 core-api 측 표현(BC 경계).
     *
     * **권한 hierarchy**: ADMIN > WRITE > READ — 선언 순서(ordinal)가 자연 표현.
     * iam-api 는 사용자에게 *단일 role* 만 부여하고, 상위 role 은 하위 권한을 자동 포함한다.
     * (예: ADMIN 사용자는 read·write·admin 모두 가능. WRITE 사용자는 read·write 가능. READ 는 read 만.)
     *
     * 신규 호출처는 enum 비교(`== ADMIN` 등) 대신 hierarchy 메서드(`canAdminister()` 등)를 사용할 것 —
     * 새 role 이 추가될 때 컴파일러가 한 곳(이 enum)만 보면 되도록.
     */
    enum class WorkspaceRole {
        READ, WRITE, ADMIN;

        /** 이 role 이 [required] 의 권한을 *포함* 하는가 (상위 또는 동등). ordinal 기반. */
        fun meetsOrExceeds(required: WorkspaceRole): Boolean = this.ordinal >= required.ordinal

        /** 읽기 가능 — 멤버면 항상 true. (`getViewerRole != null` 와 의미 동등) */
        fun canRead(): Boolean = meetsOrExceeds(READ)

        /** 콘텐츠 작성(에러케이스 생성 등) 가능 여부 — WRITE 이상. */
        fun canWriteContent(): Boolean = meetsOrExceeds(WRITE)

        /** 워크스페이스 관리(PUBLIC 승격, 멤버 관리 등) 가능 — ADMIN. */
        fun canAdminister(): Boolean = meetsOrExceeds(ADMIN)

        companion object {
            fun fromCode(code: String): WorkspaceRole? =
                entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
        }
    }
}
