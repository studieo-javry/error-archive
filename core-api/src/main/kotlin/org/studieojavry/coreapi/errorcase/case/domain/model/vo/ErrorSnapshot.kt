package org.studieojavry.coreapi.errorcase.case.domain.model.vo

class ErrorSnapshot (
    val rawPaste: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val rawStackTrace: RawStackTrace?,
    val fingeprint: Fingerprint?
) {

    fun isEmpty(): Boolean {
        return rawPaste.isNullOrEmpty() &&
            exceptionClass.isNullOrEmpty() &&
            exceptionMessage.isNullOrEmpty() &&
            rawStackTrace == null &&
            fingeprint == null
    }
}
