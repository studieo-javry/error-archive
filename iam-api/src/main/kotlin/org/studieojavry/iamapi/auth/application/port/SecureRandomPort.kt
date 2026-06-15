package org.studieojavry.iamapi.auth.application.port

interface SecureRandomPort {
    fun generateUrlSafeToken(byteLength: Int = 32): String
}