package org.studieojavry.iamapi.workspace.domain.model.vo

/**
 * 워크스페이스 단위 설정. 본 BC가 의미를 정의하는 값만 들어옴.
 * 에러 케이스 등 다른 BC의 콘텐츠 정책은 여기 들어오지 않는다.
 */
data class WorkspaceSettings(
    val notificationEnabled: Boolean = true,
    val defaultTimezone: String = "UTC"
) {
    init {
        require(defaultTimezone.isNotBlank()) { "defaultTimezone must not be blank" }
        require(defaultTimezone.length <= 64) { "defaultTimezone too long" }
    }

    companion object {
        fun defaults(): WorkspaceSettings = WorkspaceSettings()
    }
}
