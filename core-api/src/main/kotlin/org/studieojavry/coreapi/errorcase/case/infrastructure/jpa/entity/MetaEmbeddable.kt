package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class MetaEmbeddable(
    @Column(name = "meta_workspace_id")
    val workspaceId: Long? = null,

    @Column(name = "meta_severity")
    val severityCode: Int? = null,
)
