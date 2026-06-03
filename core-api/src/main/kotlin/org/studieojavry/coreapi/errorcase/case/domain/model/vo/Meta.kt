package org.studieojavry.coreapi.errorcase.case.domain.model.vo

class Meta(
    val workspaceId: Long?,
    val severity: Severity?,
    val environment: String?
) {
    companion object {
        fun create(
            workspaceId: Long?,
            severityCode: Int?,
            environment: String?
        ): Meta = Meta(
            workspaceId = workspaceId,
            severity = severityCode?.let { Severity.fromCode(it) },
            environment = environment?.takeIf { it.isNotBlank() }
        )
    }
}
