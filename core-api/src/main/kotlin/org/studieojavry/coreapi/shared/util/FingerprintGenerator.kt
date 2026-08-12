package org.studieojavry.coreapi.shared.util

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Fingerprint
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace

object FingerprintGenerator {

    fun generate(
        exceptionClass: String?,
        rawStackTrace: RawStackTrace?
    ): Fingerprint? {

        if (rawStackTrace == null) return null

        val input = buildString {
            append(exceptionClass ?: "")
            append(" | ")
            append(rawStackTrace.normalized())
        }

        val hash = HashUtils.sha256(input)
        return Fingerprint(hash)
    }
}
