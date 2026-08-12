package org.studieojavry.sharederror.problem

import java.net.URI

object ErrorTypeUri {
    private const val BASE = "https://error-archive.io/errors/"
    fun of(code: String): URI = URI.create(BASE + code.lowercase().replace('_', '-'))
}
