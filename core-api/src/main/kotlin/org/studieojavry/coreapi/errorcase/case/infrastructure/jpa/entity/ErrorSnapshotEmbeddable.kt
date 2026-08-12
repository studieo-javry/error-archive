package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class ErrorSnapshotEmbeddable(
    @Column(name = "snapshot_raw_paste", columnDefinition = "TEXT")
    val rawPaste: String? = null,

    @Column(name = "snapshot_exception_class", length = 500)
    val exceptionClass: String? = null,

    @Column(name = "snapshot_exception_message", columnDefinition = "TEXT")
    val exceptionMessage: String? = null,

    @Column(name = "snapshot_raw_stacktrace", columnDefinition = "TEXT")
    val rawStackTrace: String? = null,

    @Column(name = "snapshot_fingerprint", length = 128)
    val fingerprint: String? = null
)
