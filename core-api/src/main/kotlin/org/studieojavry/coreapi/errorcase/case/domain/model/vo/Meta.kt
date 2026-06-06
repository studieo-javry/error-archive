package org.studieojavry.coreapi.errorcase.case.domain.model.vo

/**
 * 케이스 메타데이터. *환경/태그* 자유 분류는 별도 `ErrorCase.tags` (List<String>) 로 분리됨.
 */
class Meta(
    val workspaceId: Long?,
    val severity: Severity?,
) {
    companion object {
        fun create(
            workspaceId: Long?,
            severityCode: Int?,
        ): Meta = Meta(
            workspaceId = workspaceId,
            severity = severityCode?.let { Severity.fromCode(it) },
        )
    }
}
