package org.studieojavry.iamapi.auth.infrastructure.random

import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.port.SecureRandomPort
import java.security.SecureRandom
import java.util.Base64

@Component
class SecureRandomAdapter : SecureRandomPort {

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generateUrlSafeToken(byteLength: Int): String {
        require(byteLength in 16..256) { "byteLength out of range" }
        val buffer = ByteArray(byteLength)
        random.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}