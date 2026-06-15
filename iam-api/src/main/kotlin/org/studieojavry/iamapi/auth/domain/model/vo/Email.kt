package org.studieojavry.iamapi.auth.domain.model.vo

@JvmInline
value class Email(val value: String) {
    init {
        require(value.isNotBlank()) { "email must not be blank" }
        require(EMAIL_REGEX.matches(value)) { "email format is invalid" }
        require(value.length <= 254) { "email must be 254 chars or less" }
    }

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}