package org.studieojavry.gateway.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.header.HeaderWriter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

/**
 * 게이트웨이 응답의 X-Frame-Options.
 *
 *  - 공개 페이지(`/p` 하위): SAMEORIGIN. FE 게스트 가이드가 발행물을 같은 출처 iframe 으로 띄운다.
 *  - 그 외: DENY (Spring Security 기본값과 같음).
 *
 * Spring Security 기본 frameOptions 는 모든 응답에 DENY 를 써서, publish-api 가 보낸 SAMEORIGIN 이
 * 게이트웨이를 지나면 DENY 로 바뀌었다(운영 실측 2026-11-22: publish-api 직접 SAMEORIGIN, gateway 경유 DENY).
 * 경로별 값을 게이트웨이가 직접 정한다. 외부 사이트의 프레이밍은 어느 경로든 차단된다.
 */
object FrameOptionsHeaderWriter : HeaderWriter {

    private const val HEADER = "X-Frame-Options"
    private val publicPage = PathPatternRequestMatcher.withDefaults().matcher("/p/**")

    override fun writeHeaders(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HEADER, if (publicPage.matches(request)) "SAMEORIGIN" else "DENY")
    }
}
