package org.studieojavry.iamapi.workspace.domain.model.vo

enum class WorkspaceRole(val rank: Int) {
    READ(1),
    WRITE(2),
    ADMIN(3);

    fun canManageMembers(): Boolean = this == ADMIN
    fun canEditWorkspace(): Boolean = this == ADMIN
    fun canWriteContent(): Boolean = rank >= WRITE.rank
    fun canRead(): Boolean = rank >= READ.rank

    companion object {
        fun fromCode(code: String): WorkspaceRole =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown workspace role: $code")
    }
}
