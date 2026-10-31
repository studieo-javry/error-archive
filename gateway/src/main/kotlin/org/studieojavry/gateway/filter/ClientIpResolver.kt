package org.studieojavry.gateway.filter

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestWrapper
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest

/**
 * @param trustedProxies `X-Forwarded-For` 를 믿어도 되는 TCP 피어 대역(CIDR). 비어 있으면 헤더를 믿지 않는다.
 */
@ConfigurationProperties(prefix = "gateway.client-ip")
data class ClientIpProperties(
    val trustedProxies: List<String> = emptyList(),
)

/**
 * rate limit 버킷 키로 쓰는 실 클라이언트 IP 추출.
 *
 * OCI 로드밸런서는 자신이 본 피어 주소를 `X-Forwarded-For` **맨 뒤**에 덧붙인다. 앞쪽 값은 클라이언트가
 * 보낸 그대로라 위조할 수 있으므로, TCP 피어가 [ClientIpProperties.trustedProxies] 안일 때 마지막 값만 쓴다.
 * 그 외의 접속(오리진 직결 등)은 헤더를 무시하고 피어 주소를 쓴다.
 *
 * 피어 주소는 `HttpServletRequest.remoteAddr` 를 그대로 쓰면 안 된다. `forward-headers-strategy: framework` 의
 * ForwardedHeaderFilter 가 요청을 감싸 remoteAddr 를 `X-Forwarded-For` 맨 앞 값으로 바꾸기 때문이다.
 * 래퍼를 끝까지 벗겨 컨테이너가 본 실제 소켓 주소를 읽는다.
 *
 * ⚠️ 배포 전제: 게이트웨이 Service 가 `externalTrafficPolicy: Local` 이어야 파드가 실제 피어를 본다.
 *    `Cluster` 면 SNAT 로 피어가 클러스터 내부 IP 로 뭉쳐, 모든 사용자가 한 버킷을 나눠 쓰게 된다.
 */
@Component
class ClientIpResolver(properties: ClientIpProperties) {

    private val trustedProxies = properties.trustedProxies.map(::IpAddressMatcher)

    fun resolve(request: ServerRequest): String {
        val servletRequest = request.servletRequest()
        val forwardedFor = servletRequest.getHeaders(X_FORWARDED_FOR).toList().joinToString(",")
        return resolve(socketPeerAddress(servletRequest), forwardedFor)
    }

    internal fun resolve(peer: String?, forwardedFor: String?): String {
        if (peer == null) return UNKNOWN
        val lastHop = forwardedFor?.substringAfterLast(',')?.trim()
        return if (!lastHop.isNullOrEmpty() && isTrusted(peer)) lastHop else peer
    }

    private fun isTrusted(peer: String): Boolean = trustedProxies.any { it.matches(peer) }

    internal fun socketPeerAddress(request: ServletRequest): String? {
        var current = request
        while (current is ServletRequestWrapper) current = current.request
        return current.remoteAddr
    }

    private companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val UNKNOWN = "unknown"
    }
}
