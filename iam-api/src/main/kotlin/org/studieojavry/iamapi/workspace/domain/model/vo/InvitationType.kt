package org.studieojavry.iamapi.workspace.domain.model.vo

enum class InvitationType {
    EMAIL,
    LINK;

    companion object {
        fun fromCode(code: String): InvitationType =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown invitation type: $code")
    }
}
