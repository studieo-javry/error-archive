package org.studieojavry.publishapi.publishment.presentation.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.studieojavry.publishapi.publishment.domain.PublishOptions

data class PublishRequest(
    @field:NotNull
    val caseId: Long,

    @field:Size(max = 200)
    val title: String? = null,

    @field:Size(max = 500)
    val summary: String? = null,

    /** PUBLIC (default) | UNLISTED */
    val visibility: String? = null,

    /** null = 전체 step 포함 */
    val includeStepIds: List<Long>? = null,

    val includeSolutionIds: List<Long>? = null,

    val excludedSnippetMarkerIds: List<String>? = null,
    val excludedAttachmentMarkerIds: List<String>? = null,

    val options: PublishOptions? = null,
)

/**
 * 경량 메타 편집(PATCH /by-slug/{slug}) — partial. 제공된 필드만 반영(재스냅샷 X).
 * summary: 미제공(null)=유지, "" =비우기. visibility: PUBLIC | UNLISTED.
 */
data class UpdateMetadataRequest(
    @field:Size(max = 200)
    val title: String? = null,

    @field:Size(max = 500)
    val summary: String? = null,

    val visibility: String? = null,

    val options: PublishOptions? = null,
)
