package org.studieojavry.coreapi.errorcase.shared.application.port

import org.springframework.stereotype.Repository

@Repository
interface MarkerIdGeneratorPort {

    fun generateSnippetMarkerId(): String
    fun generateAttachmentMarkerId(): String
}
