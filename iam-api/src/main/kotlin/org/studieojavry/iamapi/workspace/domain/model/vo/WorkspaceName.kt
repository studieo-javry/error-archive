package org.studieojavry.iamapi.workspace.domain.model.vo

/**
 * 워크스페이스 이름.
 *
 * **허용 문자**: 영문 대소문자 / 숫자 / 공백 / hyphen — 그 외 거부.
 * (slug 자동 정규화 (kebab-case lower) 가 손실 없이 가능하도록 제한.)
 */
@JvmInline
value class WorkspaceName(val value: String) {
    init {
        require(value.isNotBlank()) { "workspace name must not be blank" }
        require(value.length in MIN_LENGTH..MAX_LENGTH) {
            "workspace name length must be between $MIN_LENGTH and $MAX_LENGTH"
        }
        require(value.matches(PATTERN)) {
            "workspace name must contain only ASCII letters, digits, spaces, hyphens"
        }
    }

    companion object {
        const val MIN_LENGTH = 1
        const val MAX_LENGTH = 50
        val PATTERN = Regex("^[A-Za-z0-9 \\-]+$")
        const val PATTERN_STRING = "^[A-Za-z0-9 \\-]+$"
    }
}
