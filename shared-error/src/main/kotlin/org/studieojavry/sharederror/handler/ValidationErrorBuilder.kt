package org.studieojavry.sharederror.handler

import org.springframework.validation.BindingResult

object ValidationErrorBuilder {

    /** Bean Validation 의 fieldErrors → ProblemDetail extension 의 errors[] 형태. */
    fun from(bindingResult: BindingResult): List<Map<String, String>> =
        bindingResult.fieldErrors.map {
            mapOf(
                "field"   to it.field,
                "code"    to (it.code ?: "INVALID").uppercase(),
                "message" to (it.defaultMessage ?: "invalid"),
            )
        }
}
