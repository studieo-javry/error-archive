package org.studieojavry.iamapi.workspace.application.port

interface InvitationTokenGeneratorPort {
    fun generate(): String
}