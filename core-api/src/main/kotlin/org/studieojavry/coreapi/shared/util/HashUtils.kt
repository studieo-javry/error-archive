package org.studieojavry.coreapi.shared.util

import java.security.MessageDigest

object HashUtils {

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())

        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
