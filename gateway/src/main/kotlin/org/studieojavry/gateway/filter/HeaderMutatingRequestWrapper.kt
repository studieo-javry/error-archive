package org.studieojavry.gateway.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.Collections

/**
 * HttpServletRequest 의 헤더는 불변이라, 추가 헤더를 끼워 넣으려면
 * Wrapper 로 감싸 getHeader / getHeaders / getHeaderNames 를 모두 오버라이드해야 한다.
 */
class HeaderMutatingRequestWrapper(
    request: HttpServletRequest
) : HttpServletRequestWrapper(request) {

    private val customHeaders = mutableMapOf<String, String>()

    fun putHeader(name: String, value: String) {
        customHeaders[name.lowercase()] = value
    }

    override fun getHeader(name: String): String? {
        return customHeaders[name.lowercase()] ?: super.getHeader(name)
    }

    override fun getHeaders(name: String): java.util.Enumeration<String> {
        customHeaders[name.lowercase()]?.let {
            return Collections.enumeration(listOf(it))
        }
        return super.getHeaders(name)
    }

    override fun getHeaderNames(): java.util.Enumeration<String> {
        val names = mutableSetOf<String>()
        names.addAll(Collections.list(super.getHeaderNames()))
        names.addAll(customHeaders.keys)
        return Collections.enumeration(names)
    }
}
