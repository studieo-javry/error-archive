package org.studieojavry.coreapi.errorcase.case.domain.model.vo

@JvmInline
value class RawStackTrace(val value: String){
    init {
        require(value.isNotBlank()) { "Stacktrace cannot be blank" }
    }

    fun normalized(): String {
        return value
            .replace(Regex(":\\d+"), "")
            .trim()
    }
}
