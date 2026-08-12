package org.studieojavry.iamapi.workspace.domain.model.vo

/**
 * 워크스페이스 slug — URL 식별자. lowercase alnum + 단일 hyphen 구분 (kebab-case).
 * 생성 시점 고정 (immutable). 변경 시 외부 URL/링크 모두 깨지므로 정책상 변경 미허용.
 */
@JvmInline
value class WorkspaceSlug(val value: String) {
    init {
        require(value.length in MIN_LENGTH..MAX_LENGTH) {
            "slug length must be between $MIN_LENGTH and $MAX_LENGTH"
        }
        require(value.matches(PATTERN)) {
            "slug must be kebab-case: lowercase alnum, single hyphens, no leading/trailing/double hyphen"
        }
    }

    companion object {
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 40
        /** lowercase alnum start/end, 단일 hyphen 구분, double hyphen 금지. */
        val PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        const val PATTERN_STRING = "^[a-z0-9]+(-[a-z0-9]+)*$"
    }
}
