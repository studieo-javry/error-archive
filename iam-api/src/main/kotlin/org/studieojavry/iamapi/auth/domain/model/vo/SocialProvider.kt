package org.studieojavry.iamapi.auth.domain.model.vo

enum class SocialProvider {
    GITHUB,
    GOOGLE,
    APPLE;

    companion object {
        fun fromCode(code: String): SocialProvider =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
                ?: throw IllegalArgumentException("unsupported social provider: $code")
    }
}