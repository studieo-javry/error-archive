package org.studieojavry.iamapi.shared.util

import java.security.MessageDigest

object HashUtils {

    fun sha256(input: String): String = sha256(input.toByteArray(Charsets.UTF_8))

    fun sha256(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }
}