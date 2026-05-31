package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository

@Repository
interface ErrorSnaphostExtractorPort {

    fun extract(source: String): ExtractedSnapshot

    data class ExtractedSnapshot(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val rawStackTrace: String?
    )
}
