package org.studieojavry.coreapi.errorcase.case.domain.model.vo

@JvmInline
value class Fingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "fingerprint must not be blank" }
    }
}
