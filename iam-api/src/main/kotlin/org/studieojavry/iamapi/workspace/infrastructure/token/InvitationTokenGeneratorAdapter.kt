package org.studieojavry.iamapi.workspace.infrastructure.token

import org.springframework.stereotype.Component
import org.studieojavry.iamapi.workspace.application.port.InvitationTokenGeneratorPort
import java.security.SecureRandom
import java.util.Base64

@Component
class InvitationTokenGeneratorAdapter : InvitationTokenGeneratorPort {

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generate(): String {
        val buffer = ByteArray(32) // 256-bit
        random.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}