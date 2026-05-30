package org.studieojavry.coreapi.errorcase.case.domain.model.vo

enum class Severity(val code: Int, val label: String) {
    S1(1, "Outage"),
    S2(2, "Degraded"),
    S3(3, "Minor"),
    S4(4, "Info");

    companion object {
        fun fromCode(code: Int): Severity = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("invalid severity code: $code")
    }
}
