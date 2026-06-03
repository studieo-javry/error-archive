package org.studieojavry.iamapi.workspace.domain.model.vo

@JvmInline
value class WorkspaceName(val value: String) {
    init {
        require(value.isNotBlank()) { "workspace name must not be blank" }
        require(value.length in MIN_LENGTH..MAX_LENGTH) {
            "workspace name length must be between $MIN_LENGTH and $MAX_LENGTH"
        }
    }

    companion object {
        const val MIN_LENGTH = 1
        const val MAX_LENGTH = 50
    }
}
