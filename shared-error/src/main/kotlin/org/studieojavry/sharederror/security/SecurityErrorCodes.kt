package org.studieojavry.sharederror.security

object SecurityErrorCodes {
    const val TOKEN_EXPIRED          = "TOKEN_EXPIRED"
    const val INVALID_TOKEN_SIGNATURE = "INVALID_TOKEN_SIGNATURE"
    const val INVALID_TOKEN_AUDIENCE  = "INVALID_TOKEN_AUDIENCE"
    const val INVALID_TOKEN           = "INVALID_TOKEN"
    const val MISSING_TOKEN           = "MISSING_TOKEN"
    const val ACCESS_DENIED           = "ACCESS_DENIED"
}
